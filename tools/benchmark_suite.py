from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TRANSFORMERS_NO_TF", "1")

import librosa
import numpy as np
import pandas as pd
import safetensors.torch
import soundfile as sf
import torch
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from tensorflow import keras
from transformers import AutoConfig, Wav2Vec2ForAudioFrameClassification, Wav2Vec2ForCTC, Wav2Vec2Processor


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "output" / "benchmarks"
ADD_ASR_ROOT = ROOT / "add_asr" / "output"


def load_manifest(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def clip_pred_from_segments(segments: list[dict[str, float]]) -> int:
    return int(bool(segments))


def score_metrics(y_true: list[int], y_pred: list[int]) -> dict[str, float]:
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision": float(precision_score(y_true, y_pred, zero_division=0)),
        "recall": float(recall_score(y_true, y_pred, zero_division=0)),
        "f1": float(f1_score(y_true, y_pred, zero_division=0)),
        "num_items": int(len(y_true)),
        "num_positive_ref": int(sum(y_true)),
        "num_positive_pred": int(sum(y_pred)),
    }


class GillickDetector:
    def __init__(self) -> None:
        sys.path.insert(0, str(ROOT / "gillick"))
        sys.path.insert(0, str(ROOT / "gillick" / "utils"))
        import configs  # type: ignore
        import laugh_segmenter  # type: ignore
        import models  # type: ignore
        import audio_utils  # type: ignore
        import data_loaders  # type: ignore
        import torch_utils  # type: ignore
        from functools import partial

        self.config = configs.CONFIG_MAP["resnet_with_augmentation"]
        self.laugh_segmenter = laugh_segmenter
        self.audio_utils = audio_utils
        self.data_loaders = data_loaders
        self.partial = partial
        self.sample_rate = 8000
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model = self.config["model"](
            dropout_rate=0.0,
            linear_layer_size=self.config["linear_layer_size"],
            filter_sizes=self.config["filter_sizes"],
        )
        self.model.set_device(self.device)
        ckpt_dir = ROOT / "gillick" / "checkpoints" / "in_use" / "resnet_with_augmentation" / "best.pth.tar"
        torch_utils.load_checkpoint(str(ckpt_dir), self.model)
        self.model.eval()

    def predict(self, audio_path: str, threshold: float) -> list[dict[str, float]]:
        inference_dataset = self.data_loaders.SwitchBoardLaughterInferenceDataset(
            audio_path=audio_path,
            feature_fn=self.config["feature_fn"],
            sr=self.sample_rate,
        )
        probs: list[float] = []
        if len(inference_dataset.features) <= inference_dataset.n_frames:
            features = inference_dataset.features
            if len(features) < inference_dataset.n_frames:
                pad = np.zeros(
                    (inference_dataset.n_frames - len(features), features.shape[1]),
                    dtype=features.dtype,
                )
                features = np.concatenate([features, pad], axis=0)
            model_inputs = np.expand_dims(features[: inference_dataset.n_frames], axis=0)
            if self.config["expand_channel_dim"]:
                model_inputs = np.expand_dims(model_inputs, 1)
            preds = self.model(torch.from_numpy(model_inputs).float().to(self.device)).detach().cpu().numpy().squeeze()
            probs.append(float(preds))
        else:
            collate_fn = self.partial(
                self.audio_utils.pad_sequences_with_labels,
                expand_channel_dim=self.config["expand_channel_dim"],
            )
            loader = torch.utils.data.DataLoader(
                inference_dataset,
                num_workers=0,
                batch_size=8,
                shuffle=False,
                collate_fn=collate_fn,
            )
            for model_inputs, _ in loader:
                x = torch.from_numpy(model_inputs).float().to(self.device)
                preds = self.model(x).detach().cpu().numpy().squeeze()
                if np.ndim(preds) == 0:
                    probs.append(float(preds))
                else:
                    probs.extend(float(item) for item in preds)
        file_length = self.audio_utils.get_audio_length(audio_path)
        fps = len(probs) / float(file_length)
        filtered = np.asarray(probs)
        if len(filtered) > 9:
            filtered = self.laugh_segmenter.lowpass(filtered)
        instances = self.laugh_segmenter.get_laughter_instances(filtered, threshold=threshold, min_length=0.2, fps=fps)
        return [
            {"start": float(start), "end": float(end), "duration": float(end - start)}
            for start, end in instances
        ]


class OmineDetector:
    def __init__(self) -> None:
        sys.path.insert(0, str(ROOT / "omine" / "LaughterSegmentation"))
        from evaluation._utils.utils import concat_close, remove_short  # type: ignore

        self.concat_close = concat_close
        self.remove_short = remove_short
        self.device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
        self.sr = 16000
        self.input_sec = 7.0
        self.overlap_sec = 2.0
        self.batch_size = 10
        config = AutoConfig.from_pretrained(
            "jonatasgrosman/wav2vec2-large-xlsr-53-english",
            num_labels=1,
            problem_type="single_label_classification",
        )
        self.model = Wav2Vec2ForAudioFrameClassification(config).to(self.device)
        state_dict = safetensors.torch.load_file(
            str(ROOT / "omine" / "LaughterSegmentation" / "models" / "model.safetensors"),
            self.device.index if self.device.type == "cuda" else "cpu",
        )
        if any(key.startswith("audio_model.") for key in state_dict):
            state_dict = {
                key.removeprefix("audio_model."): value
                for key, value in state_dict.items()
                if key.startswith("audio_model.")
            }
        self.model.load_state_dict(state_dict)
        self.model.eval()

    def _load_audio(self, audio_path: str) -> np.ndarray:
        audio_array, sr = sf.read(audio_path, always_2d=False)
        audio_array = np.asarray(audio_array, dtype=np.float32)
        if audio_array.ndim > 1:
            audio_array = np.mean(audio_array, axis=1 if audio_array.shape[0] < audio_array.shape[1] else 0)
        if sr != self.sr:
            audio_array = librosa.resample(audio_array, orig_sr=sr, target_sr=self.sr)
        return librosa.util.normalize(audio_array)

    def predict(self, audio_path: str, threshold: float) -> list[dict[str, float]]:
        audio_array = self._load_audio(audio_path)
        laughter: dict[str, dict[str, float]] = {}
        laughter_idx = 0
        hop = int(self.sr * (self.input_sec - self.overlap_sec))
        with torch.no_grad():
            for array_idx in range(0, len(audio_array), hop * self.batch_size):
                batched_arrays = []
                should_break = False
                for batch_idx in range(self.batch_size):
                    start = array_idx + batch_idx * hop
                    end = start + int(self.sr * self.input_sec)
                    array = audio_array[start:end]
                    if len(array) < int(self.sr * self.input_sec):
                        array = np.append(array, np.zeros(int(self.sr * self.input_sec) - len(array)))
                        should_break = True
                    batched_arrays.append(array)
                    if should_break:
                        break
                logits = self.model(input_values=torch.from_numpy(np.asarray(batched_arrays)).float().to(self.device))[
                    "logits"
                ].squeeze(dim=2)
                preds = torch.sigmoid(logits.to(torch.float32))
                for batch_idx, pred in enumerate(preds):
                    frame_pred = np.asarray(pred.detach().cpu().tolist(), dtype=np.float32)
                    frame_bin = (frame_pred >= threshold).astype(int)
                    batch_start = (array_idx + batch_idx * hop) / float(self.sr)
                    frame_count = len(frame_bin)
                    start_idx = None
                    status = 0
                    for idx, frame in enumerate(frame_bin):
                        if frame == 1 and status == 0:
                            start_idx = idx
                            status = 1
                        if frame == 0 and status == 1 and start_idx is not None:
                            laughter[str(laughter_idx)] = {
                                "start_sec": batch_start + (self.input_sec / frame_count) * start_idx,
                                "end_sec": batch_start + (self.input_sec / frame_count) * idx,
                            }
                            laughter_idx += 1
                            start_idx = None
                            status = 0
                    if status == 1 and start_idx is not None:
                        laughter[str(laughter_idx)] = {
                            "start_sec": batch_start + (self.input_sec / frame_count) * start_idx,
                            "end_sec": batch_start + self.input_sec,
                        }
                        laughter_idx += 1
        merged = self.concat_close(laughter, 0.2)
        merged = self.remove_short(merged, 0.2)
        return [
            {
                "start": float(event["start_sec"]),
                "end": float(event["end_sec"]),
                "duration": float(event["end_sec"] - event["start_sec"]),
            }
            for event in merged.values()
        ]


class IdeoDetector:
    def __init__(self) -> None:
        sys.path.insert(0, str(ROOT / "ideo"))
        from audioset.vggish_embeddings import VGGishEmbedder  # type: ignore

        self.segment_length = 3.0
        self.model_path = ROOT / "ideo" / "Models" / "LSTM_SingleLayer_100Epochs.h5"
        self.model = keras.models.load_model(self.model_path, compile=False)
        self.embedder = VGGishEmbedder(None)

    def _chunk(self, audio_path: str) -> list[tuple[float, float, np.ndarray, int]]:
        audio, sr = sf.read(audio_path, always_2d=False)
        audio = np.asarray(audio, dtype=np.float32)
        if audio.ndim > 1:
            audio = np.mean(audio, axis=1 if audio.shape[0] < audio.shape[1] else 0)
        chunk_samples = max(1, int(round(self.segment_length * sr)))
        chunks = []
        for start in range(0, len(audio), chunk_samples):
            end = min(len(audio), start + chunk_samples)
            chunk = audio[start:end]
            if chunk.size:
                chunks.append((start / float(sr), end / float(sr), chunk, sr))
        return chunks

    def predict(self, audio_path: str, threshold: float) -> list[dict[str, float]]:
        chunks = self._chunk(audio_path)
        embeddings = []
        valid_chunks = []
        max_len = 0
        for chunk_info in chunks:
            _, _, chunk, sr = chunk_info
            chunk_i16 = np.clip(chunk * 32768.0, -32768, 32767).astype(np.int16)
            embedding = self.embedder.convert_waveform_to_embedding(chunk_i16, sr)
            if embedding.ndim == 1:
                embedding = np.expand_dims(embedding, axis=0)
            if embedding.size == 0:
                continue
            embeddings.append(embedding)
            valid_chunks.append(chunk_info)
            max_len = max(max_len, embedding.shape[0])
        if not embeddings:
            return []
        padded = np.asarray(
            [np.append(e, np.zeros((max_len - e.shape[0], 128), np.float32), axis=0) for e in embeddings]
        )
        scores = self.model.predict(padded, verbose=0)[:, 0]
        segments = []
        for (start, end, _, _), score in zip(valid_chunks, scores):
            if float(score) >= threshold:
                segments.append(
                    {"start": float(start), "end": float(end), "duration": float(end - start), "score": float(score)}
                )
        return segments


class HhoangphuocDetector:
    def __init__(self) -> None:
        model_dir = ROOT / "hhoangphuoc" / "fine-tuned" / "wav2vec2" / "finetuned-wav2vec2+FT+L"
        self.processor = Wav2Vec2Processor.from_pretrained(model_dir)
        self.model = Wav2Vec2ForCTC.from_pretrained(model_dir)
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)
        self.model.eval()

    @staticmethod
    def _is_positive_text(text: str) -> bool:
        tokens = text.split()
        return any(token == "<laugh>" for token in tokens) or any(token.isupper() for token in tokens)

    def transcribe(self, audio_path: str) -> str:
        audio, sr = sf.read(audio_path, always_2d=False)
        audio = np.asarray(audio, dtype=np.float32)
        if audio.ndim > 1:
            audio = np.mean(audio, axis=1 if audio.shape[0] < audio.shape[1] else 0)
        if sr != 16000:
            audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
        inputs = self.processor(audio, sampling_rate=16000, return_tensors="pt", padding=True)
        with torch.no_grad():
            logits = self.model(inputs.input_values.to(self.device)).logits
        pred_ids = torch.argmax(logits, dim=-1)
        text = self.processor.batch_decode(pred_ids)[0].replace("<", " <laugh> ").strip()
        return " ".join(text.split())

    def predict(self, audio_path: str, threshold: float | None = None) -> list[dict[str, float]]:
        transcript = self.transcribe(audio_path)
        info = sf.info(audio_path)
        if self._is_positive_text(transcript):
            return [{"start": 0.0, "end": float(info.duration), "duration": float(info.duration), "transcript": transcript}]
        return []


class SpeechLaughFilter:
    def __init__(self) -> None:
        self.detector = HhoangphuocDetector()

    def keep(self, audio_path: str) -> bool:
        return clip_pred_from_segments(self.detector.predict(audio_path)) == 1


class SpeechmaticsDetector:
    def __init__(self) -> None:
        sys.path.insert(0, str(ROOT))
        speechmatics_root = ROOT / "speechmatics_demo"
        if not speechmatics_root.exists():
            raise ModuleNotFoundError(
                "speechmatics_demo package is not available. Make sure the "
                "'speechmatics_demo/' directory exists under the project root "
                "on the server."
            )
        try:
            from speechmatics_demo.app.config import Settings  # type: ignore
            from speechmatics_demo.app.service import RealtimeLaughterService  # type: ignore
        except ModuleNotFoundError as exc:
            raise ModuleNotFoundError(
                f"Speechmatics benchmark dependency is missing: {exc.name}. "
                "Install speechmatics_demo requirements on the server with "
                "'pip install -r speechmatics_demo/requirements.txt'."
            ) from exc

        self.settings = Settings.from_env()
        self.service = RealtimeLaughterService(self.settings)

    @staticmethod
    def _segments_from_events(events: list[dict[str, object]], min_confidence: float) -> list[dict[str, float]]:
        segments: list[dict[str, float]] = []
        open_events: list[dict[str, object]] = []

        for event in events:
            phase = event.get("phase")
            if phase == "started":
                open_events.append(event)
                continue
            if phase != "ended":
                continue
            # confidence is on the 'started' event; fall back to ended if present
            started = open_events[-1] if open_events else {}
            confidence = float(started.get("confidence") or event.get("confidence") or 0.0)
            if confidence < min_confidence:
                open_events.clear()
                continue

            start_time = event.get("start_time")
            end_time = event.get("end_time")
            if start_time is None and open_events:
                start_time = open_events[-1].get("start_time")
            if start_time is None or end_time is None:
                continue
            start_f = float(start_time)
            end_f = float(end_time)
            if end_f <= start_f:
                continue
            segments.append(
                {
                    "start": start_f,
                    "end": end_f,
                    "duration": float(end_f - start_f),
                    "confidence": confidence,
                }
            )

        return segments

    def predict(self, audio_path: str, threshold: float | None) -> list[dict[str, float]]:
        import asyncio

        min_confidence = 0.0 if threshold is None else float(threshold)
        wav_bytes = Path(audio_path).read_bytes()

        def _silence_connection_reset(loop: asyncio.AbstractEventLoop, context: dict) -> None:
            exc = context.get("exception")
            if isinstance(exc, ConnectionResetError):
                return
            loop.default_exception_handler(context)

        max_retries = 3
        retry_delay = 15.0
        last_exc: Exception | None = None
        for attempt in range(1, max_retries + 1):
            loop = asyncio.new_event_loop()
            loop.set_exception_handler(_silence_connection_reset)
            try:
                payload = loop.run_until_complete(
                    self.service.process_wav_bytes(
                        wav_bytes=wav_bytes,
                        source_name=f"benchmark:{Path(audio_path).name}",
                        chunk_ms=200,
                        pace_realtime=False,
                    )
                )
                return self._segments_from_events(payload.get("events", []), min_confidence=min_confidence)
            except Exception as exc:
                last_exc = exc
                print(f"[speechmatics] Attempt {attempt}/{max_retries} failed for {Path(audio_path).name}: {exc}")
                if attempt < max_retries:
                    print(f"[speechmatics] Retrying in {retry_delay}s...")
                    time.sleep(retry_delay)
            finally:
                loop.close()

        raise RuntimeError(f"All {max_retries} attempts failed for {audio_path}") from last_exc


DETECTOR_FACTORIES: dict[str, Callable[[], object]] = {
    "gillick": GillickDetector,
    "omine": OmineDetector,
    "ideo": IdeoDetector,
    "hhoangphuoc": HhoangphuocDetector,
    "speechmatics": SpeechmaticsDetector,
}


@dataclass
class BenchResult:
    method: str
    threshold: str
    metrics: dict[str, float]
    average_latency_ms: float
    median_latency_ms: float


def run_method(
    method_name: str,
    detector: object,
    manifest_rows: list[dict[str, str]],
    threshold: float | None,
    output_dir: Path,
    inter_request_delay: float = 0.0,
) -> BenchResult:
    y_true: list[int] = []
    y_pred: list[int] = []
    latencies_ms: list[float] = []
    records = []

    total_files = len(manifest_rows)
    print(f"[run_method] Total files in manifest: {total_files}")

    for index, row in enumerate(manifest_rows, start=1):
        label = row["label"]
        is_positive = int(label in {"laughter", "speechlaugh"})

        start = time.perf_counter()
        try:
            segments = detector.predict(row["audio_path"], threshold)  # type: ignore[attr-defined]
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            pred = clip_pred_from_segments(segments)
            y_true.append(is_positive)
            y_pred.append(pred)
            latencies_ms.append(elapsed_ms)
            records.append(
                {
                    "id": row["id"],
                    "label": label,
                    "predicted_positive": pred,
                    "latency_ms": round(elapsed_ms, 3),
                    "num_segments": len(segments),
                    "segments": json.dumps(segments, ensure_ascii=False),
                }
            )
        except Exception as e:
            print(f"[run_method] Error processing file {index}/{total_files} ({row['audio_path']}): {e}")
            import traceback
            traceback.print_exc()
            continue

        if inter_request_delay > 0 and index < total_files:
            time.sleep(inter_request_delay)

        if index % 50 == 0 or index == total_files:
            print(f"[run_method] {method_name} threshold={threshold} processed {index}/{total_files}", flush=True)

    print(f"[run_method] Processed {len(records)} out of {total_files} files")

    metrics = score_metrics(y_true, y_pred)
    metrics["average_latency_ms"] = float(np.mean(latencies_ms))
    metrics["median_latency_ms"] = float(np.median(latencies_ms))
    output_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame.from_records(records).to_csv(output_dir / "predictions.csv", index=False)
    (output_dir / "metrics.json").write_text(
        json.dumps(
            {
                "method": method_name,
                "threshold": threshold,
                "metrics": metrics,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    return BenchResult(
        method=method_name,
        threshold="na" if threshold is None else str(threshold),
        metrics=metrics,
        average_latency_ms=float(np.mean(latencies_ms)),
        median_latency_ms=float(np.median(latencies_ms)),
    )


def run_asr_filter(
    base_method: str,
    threshold: float,
    manifest_rows: list[dict[str, str]],
    benchmark_root: Path,
) -> BenchResult:
    filter_name = "speechlaugh_ctc_filter"
    filter_impl = SpeechLaughFilter()
    base_predictions = pd.read_csv(benchmark_root / base_method / f"threshold_{threshold}" / "predictions.csv")
    merged = pd.DataFrame(manifest_rows).merge(base_predictions, on="id", how="inner", suffixes=("", "_pred"))

    y_true: list[int] = []
    y_pred: list[int] = []
    latencies_ms: list[float] = []
    records = []

    records_iter = merged.to_dict("records")
    total = len(records_iter)
    for index, row in enumerate(records_iter, start=1):
        label = row.get("label", row.get("label_pred"))
        is_positive = int(label in {"laughter", "speechlaugh"})
        pred = int(row["predicted_positive"])
        keep = 0
        elapsed_ms = 0.0
        if pred == 1:
            start = time.perf_counter()
            keep = int(filter_impl.keep(row["audio_path"]))
            elapsed_ms = (time.perf_counter() - start) * 1000.0
        y_true.append(is_positive)
        y_pred.append(keep if pred == 1 else 0)
        latencies_ms.append(elapsed_ms)
        records.append(
            {
                "id": row["id"],
                "label": label,
                "base_positive": pred,
                "filtered_positive": keep if pred == 1 else 0,
                "filter_latency_ms": round(elapsed_ms, 3),
            }
        )
        if index % 50 == 0 or index == total:
            print(
                f"[run_asr_filter] {base_method}+{filter_name} threshold={threshold} processed {index}/{total}",
                flush=True,
            )

    metrics = score_metrics(y_true, y_pred)
    metrics["average_filter_latency_ms"] = float(np.mean(latencies_ms))
    metrics["median_filter_latency_ms"] = float(np.median(latencies_ms))
    out_dir = ADD_ASR_ROOT / "switchboard" / base_method / filter_name / f"threshold_{threshold}"
    out_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame.from_records(records).to_csv(out_dir / "predictions.csv", index=False)
    (out_dir / "metrics.json").write_text(
        json.dumps(
            {
                "base_method": base_method,
                "filter": filter_name,
                "threshold": threshold,
                "metrics": metrics,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    return BenchResult(
        method=f"{base_method}+{filter_name}",
        threshold=str(threshold),
        metrics=metrics,
        average_latency_ms=float(np.mean(latencies_ms)),
        median_latency_ms=float(np.median(latencies_ms)),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Unified clip-level laughter benchmark.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--dataset-name", default="switchboard")
    parser.add_argument("--methods", nargs="*", default=["gillick", "omine", "ideo", "hhoangphuoc"])
    parser.add_argument("--thresholds", nargs="*", type=float, default=[0.5, 0.65])
    parser.add_argument("--run-asr-filter", action="store_true")
    parser.add_argument("--inter-request-delay", type=float, default=None,
                        help="Seconds to wait between requests (default: 1.0 for speechmatics, 0 otherwise)")
    args = parser.parse_args()

    manifest_rows = load_manifest(args.manifest)
    benchmark_root = OUTPUT_ROOT / args.dataset_name
    benchmark_root.mkdir(parents=True, exist_ok=True)

    summary: list[dict[str, object]] = []
    for method_name in args.methods:
        detector = DETECTOR_FACTORIES[method_name]()
        thresholds = [None] if method_name == "hhoangphuoc" else args.thresholds
        if args.inter_request_delay is not None:
            delay = args.inter_request_delay
        else:
            delay = 10.0 if method_name == "speechmatics" else 0.0
        for threshold in thresholds:
            tag = "threshold_na" if threshold is None else f"threshold_{threshold}"
            result = run_method(method_name, detector, manifest_rows, threshold, benchmark_root / method_name / tag, inter_request_delay=delay)
            summary.append(
                {
                    "method": result.method,
                    "threshold": result.threshold,
                    **result.metrics,
                }
            )
        if args.run_asr_filter and method_name in {"gillick", "omine", "ideo"}:
            for threshold in args.thresholds:
                result = run_asr_filter(method_name, threshold, manifest_rows, benchmark_root)
                summary.append(
                    {
                        "method": result.method,
                        "threshold": result.threshold,
                        **result.metrics,
                    }
                )

    pd.DataFrame.from_records(summary).to_csv(benchmark_root / "summary.csv", index=False)
    print(pd.DataFrame.from_records(summary).to_string(index=False))


if __name__ == "__main__":
    main()
