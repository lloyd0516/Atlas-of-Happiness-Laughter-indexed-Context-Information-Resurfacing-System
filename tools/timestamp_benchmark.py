from __future__ import annotations

import argparse
import csv
import json
import tempfile
import time
from pathlib import Path

import numpy as np
import pandas as pd
import soundfile as sf

from benchmark_suite import (
    ADD_ASR_ROOT,
    OUTPUT_ROOT,
    GillickDetector,
    HhoangphuocDetector,
    IdeoDetector,
    OmineDetector,
)


ROOT = Path(__file__).resolve().parents[1]


def load_manifest(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_segments(path: str) -> list[dict[str, float]]:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    return payload.get("segments", [])


def overlap_amount(start1: float, end1: float, start2: float, end2: float) -> float:
    return max(0.0, min(end1, end2) - max(start1, start2))


def sum_overlap_amount(true_segments: list[dict[str, float]], pred_segments: list[dict[str, float]]) -> float:
    total = 0.0
    for ts in true_segments:
        for ps in pred_segments:
            total += overlap_amount(ts["start"], ts["end"], ps["start"], ps["end"])
    return total


def merge_segments(segments: list[dict[str, float]], max_gap: float = 0.2) -> list[dict[str, float]]:
    if not segments:
        return []
    ordered = sorted(segments, key=lambda seg: (seg["start"], seg["end"]))
    merged = [ordered[0].copy()]
    for seg in ordered[1:]:
        last = merged[-1]
        if seg["start"] <= last["end"] + max_gap:
            last["end"] = max(last["end"], seg["end"])
        else:
            merged.append(seg.copy())
    for seg in merged:
        seg["duration"] = float(seg["end"] - seg["start"])
    return merged


def complement_segments(segments: list[dict[str, float]], duration: float) -> list[dict[str, float]]:
    segments = merge_segments(segments)
    output = []
    cursor = 0.0
    for seg in segments:
        if seg["start"] > cursor:
            output.append({"start": cursor, "end": seg["start"], "duration": seg["start"] - cursor})
        cursor = max(cursor, seg["end"])
    if cursor < duration:
        output.append({"start": cursor, "end": duration, "duration": duration - cursor})
    return output


def score_time_metrics(tp: list[float], fp: list[float], tn: list[float], fn: list[float]) -> dict[str, float]:
    tp_t = float(sum(tp))
    fp_t = float(sum(fp))
    tn_t = float(sum(tn))
    fn_t = float(sum(fn))
    precision = tp_t / (tp_t + fp_t) if tp_t + fp_t > 0 else 0.0
    recall = tp_t / (tp_t + fn_t) if tp_t + fn_t > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall > 0 else 0.0
    accuracy = (tp_t + tn_t) / (tp_t + fp_t + tn_t + fn_t) if tp_t + fp_t + tn_t + fn_t > 0 else 0.0
    return {
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "tp_time": tp_t,
        "fp_time": fp_t,
        "tn_time": tn_t,
        "fn_time": fn_t,
    }


class HhoangphuocWindowDetector:
    def __init__(self, window_sec: float = 5.0, hop_sec: float = 2.0) -> None:
        self.base = HhoangphuocDetector()
        self.window_sec = window_sec
        self.hop_sec = hop_sec

    def predict(self, audio_path: str, threshold: float | None = None) -> list[dict[str, float]]:
        audio, sr = sf.read(audio_path, always_2d=False)
        audio = np.asarray(audio, dtype=np.float32)
        if audio.ndim > 1:
            audio = np.mean(audio, axis=1 if audio.shape[0] < audio.shape[1] else 0)
        win = max(1, int(round(self.window_sec * sr)))
        hop = max(1, int(round(self.hop_sec * sr)))
        segs = []
        with tempfile.TemporaryDirectory(dir=str(ROOT / "output")) as tmpdir:
            tmpdir_path = Path(tmpdir)
            for start in range(0, len(audio), hop):
                end = min(len(audio), start + win)
                clip = audio[start:end]
                if clip.size == 0:
                    continue
                tmp_path = tmpdir_path / f"chunk_{start}.wav"
                sf.write(str(tmp_path), clip, sr)
                if self.base.predict(str(tmp_path)):
                    segs.append({"start": start / sr, "end": end / sr, "duration": (end - start) / sr})
                if end == len(audio):
                    break
        return merge_segments(segs, max_gap=0.25)


DETECTORS = {
    "gillick": GillickDetector,
    "omine": OmineDetector,
    "ideo": IdeoDetector,
    "hhoangphuoc": HhoangphuocWindowDetector,
}


def evaluate_method(rows: list[dict[str, str]], method_name: str, threshold: float | None, out_dir: Path) -> dict[str, float]:
    detector = DETECTORS[method_name]()
    tp_times = []
    fp_times = []
    tn_times = []
    fn_times = []
    latency_ms = []
    records = []
    for index, row in enumerate(rows, start=1):
        true_segments = read_segments(row["annotation_path"])
        duration = float(row["duration_sec"])
        start = time.perf_counter()
        pred_segments = detector.predict(row["audio_path"], threshold)  # type: ignore[attr-defined]
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        true_non = complement_segments(true_segments, duration)
        pred_non = complement_segments(pred_segments, duration)
        tp = sum_overlap_amount(true_segments, pred_segments)
        fp = sum_overlap_amount(true_non, pred_segments)
        tn = sum_overlap_amount(true_non, pred_non)
        fn = sum_overlap_amount(true_segments, pred_non)
        tp_times.append(tp)
        fp_times.append(fp)
        tn_times.append(tn)
        fn_times.append(fn)
        latency_ms.append(elapsed_ms)
        records.append(
            {
                "id": row["id"],
                "latency_ms": round(elapsed_ms, 3),
                "tp_time": tp,
                "fp_time": fp,
                "tn_time": tn,
                "fn_time": fn,
                "predicted_segments": json.dumps(pred_segments),
                "true_segments": json.dumps(true_segments),
            }
        )
        if index % 5 == 0 or index == len(rows):
            print(f"[timestamp_eval] {method_name} threshold={threshold} processed {index}/{len(rows)}", flush=True)
    metrics = score_time_metrics(tp_times, fp_times, tn_times, fn_times)
    metrics["average_latency_ms"] = float(np.mean(latency_ms))
    metrics["median_latency_ms"] = float(np.median(latency_ms))
    out_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame.from_records(records).to_csv(out_dir / "predictions.csv", index=False)
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    return metrics


def filter_segments(audio_path: str, segments: list[dict[str, float]], filter_impl: HhoangphuocWindowDetector) -> list[dict[str, float]]:
    audio, sr = sf.read(audio_path, always_2d=False)
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim > 1:
        audio = np.mean(audio, axis=1 if audio.shape[0] < audio.shape[1] else 0)
    kept = []
    with tempfile.TemporaryDirectory(dir=str(ROOT / "add_asr")) as tmpdir:
        for index, seg in enumerate(segments):
            start = int(round(seg["start"] * sr))
            end = int(round(seg["end"] * sr))
            clip = audio[start:end]
            if clip.size == 0:
                continue
            tmp_path = Path(tmpdir) / f"seg_{index}.wav"
            sf.write(str(tmp_path), clip, sr)
            if filter_impl.predict(str(tmp_path)):
                kept.append(seg)
    return merge_segments(kept, max_gap=0.2)


def evaluate_filter(rows: list[dict[str, str]], base_method: str, threshold: float, base_dir: Path, out_dir: Path) -> dict[str, float]:
    preds = pd.read_csv(base_dir / f"threshold_{threshold}" / "predictions.csv")
    pred_map = {row["id"]: json.loads(row["predicted_segments"]) for _, row in preds.iterrows()}
    filter_impl = HhoangphuocWindowDetector()
    tp_times = []
    fp_times = []
    tn_times = []
    fn_times = []
    latency_ms = []
    records = []
    for index, row in enumerate(rows, start=1):
        true_segments = read_segments(row["annotation_path"])
        duration = float(row["duration_sec"])
        base_segments = pred_map[row["id"]]
        start = time.perf_counter()
        filtered_segments = filter_segments(row["audio_path"], base_segments, filter_impl)
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        true_non = complement_segments(true_segments, duration)
        pred_non = complement_segments(filtered_segments, duration)
        tp = sum_overlap_amount(true_segments, filtered_segments)
        fp = sum_overlap_amount(true_non, filtered_segments)
        tn = sum_overlap_amount(true_non, pred_non)
        fn = sum_overlap_amount(true_segments, pred_non)
        tp_times.append(tp)
        fp_times.append(fp)
        tn_times.append(tn)
        fn_times.append(fn)
        latency_ms.append(elapsed_ms)
        records.append(
            {
                "id": row["id"],
                "filter_latency_ms": round(elapsed_ms, 3),
                "tp_time": tp,
                "fp_time": fp,
                "tn_time": tn,
                "fn_time": fn,
                "filtered_segments": json.dumps(filtered_segments),
            }
        )
        if index % 5 == 0 or index == len(rows):
            print(f"[timestamp_filter] {base_method} threshold={threshold} processed {index}/{len(rows)}", flush=True)
    metrics = score_time_metrics(tp_times, fp_times, tn_times, fn_times)
    metrics["average_filter_latency_ms"] = float(np.mean(latency_ms))
    metrics["median_filter_latency_ms"] = float(np.median(latency_ms))
    out_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame.from_records(records).to_csv(out_dir / "predictions.csv", index=False)
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Unified timestamp-level laughter benchmark.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--dataset-name", required=True)
    args = parser.parse_args()
    rows = load_manifest(args.manifest)
    out_root = ROOT / "output" / "timestamp_benchmarks" / args.dataset_name
    add_asr_root = ROOT / "add_asr" / "output" / "timestamp_benchmarks" / args.dataset_name
    summary = []
    for method in ["gillick", "omine", "ideo", "hhoangphuoc"]:
        thresholds = [None] if method == "hhoangphuoc" else [0.5, 0.65]
        for threshold in thresholds:
            tag = "threshold_na" if threshold is None else f"threshold_{threshold}"
            metrics = evaluate_method(rows, method, threshold, out_root / method / tag)
            summary.append({"method": method, "threshold": "na" if threshold is None else threshold, **metrics})
        if method in {"gillick", "omine", "ideo"}:
            for threshold in [0.5, 0.65]:
                metrics = evaluate_filter(
                    rows,
                    method,
                    threshold,
                    out_root / method,
                    add_asr_root / method / "speechlaugh_ctc_filter" / f"threshold_{threshold}",
                )
                summary.append(
                    {"method": f"{method}+speechlaugh_ctc_filter", "threshold": threshold, **metrics}
                )
    pd.DataFrame.from_records(summary).to_csv(out_root / "summary.csv", index=False)
    print(pd.DataFrame.from_records(summary).to_string(index=False))


if __name__ == "__main__":
    main()
