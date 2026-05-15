from __future__ import annotations

import argparse
import csv
import json
import subprocess
import tempfile
from pathlib import Path

import imageio_ffmpeg
import pandas as pd
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1]
AUDIOSET_ROOT = ROOT / "datasets_unified" / "audioset"
GILLICK_AUDIOSET = ROOT / "gillick" / "data" / "audioset"


def load_clean_annotations() -> tuple[pd.DataFrame, pd.DataFrame]:
    laughter = pd.read_csv(GILLICK_AUDIOSET / "annotations" / "clean_laughter_annotations.csv")
    distractor = pd.read_csv(GILLICK_AUDIOSET / "annotations" / "clean_distractor_annotations.csv")
    return laughter, distractor


def load_split_ids(name: str) -> set[str]:
    path = GILLICK_AUDIOSET / "splits" / name
    return {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}


def build_eval_manifest(limit_per_label: int | None = None) -> pd.DataFrame:
    laughter_df, distractor_df = load_clean_annotations()
    test_laughter_ids = load_split_ids("test_laughter_ids.txt")
    test_negative_ids = load_split_ids("test_negative_ids.txt")

    laughter_df = laughter_df[laughter_df["FileID"].isin(test_laughter_ids)].copy()
    distractor_df = distractor_df[distractor_df["FileID"].isin(test_negative_ids)].copy()
    laughter_df["label"] = "laughter"
    distractor_df["label"] = "speech"

    if limit_per_label is not None:
        laughter_df = laughter_df.head(limit_per_label)
        distractor_df = distractor_df.head(limit_per_label)

    df = pd.concat([laughter_df, distractor_df], ignore_index=True)
    df["ytid"] = df["FileID"]
    df["start_seconds"] = 0.0
    df["end_seconds"] = df["audio_length"].astype(float)
    df["clip_duration_sec"] = df["audio_length"].astype(float)
    return df[
        [
            "ytid",
            "label",
            "clip_duration_sec",
            "start_seconds",
            "end_seconds",
            "audio_length",
            "window_start",
            "window_length",
            "Start",
            "End",
            "Start.1",
            "End.1",
            "Start.2",
            "End.2",
            "Start.3",
            "End.3",
            "Start.4",
            "End.4",
        ]
    ].reset_index(drop=True)


def ffmpeg_trim_to_wav(source_path: Path, target_path: Path, start: float, end: float) -> None:
    ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
    duration = max(0.1, end - start)
    cmd = [
        ffmpeg_exe,
        "-y",
        "-ss",
        str(start),
        "-i",
        str(source_path),
        "-t",
        str(duration),
        "-ac",
        "1",
        "-ar",
        "16000",
        str(target_path),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def download_youtube_audio(ytid: str, output_wav: Path, start: float, end: float) -> str:
    output_wav.parent.mkdir(parents=True, exist_ok=True)
    if output_wav.exists():
        return "exists"

    with tempfile.TemporaryDirectory(dir=str(AUDIOSET_ROOT)) as tmpdir:
        tmpdir_path = Path(tmpdir)
        raw_template = str(tmpdir_path / f"{ytid}.%(ext)s")
        url = f"https://www.youtube.com/watch?v={ytid}"
        ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
        cmd = [
            str(ROOT / "env" / "ld_main" / "bin" / "python"),
            "-m",
            "yt_dlp",
            "--ffmpeg-location",
            str(ffmpeg_exe),
            "--extract-audio",
            "--audio-format",
            "wav",
            "--audio-quality",
            "0",
            "-o",
            raw_template,
            url,
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        candidates = sorted(tmpdir_path.glob(f"{ytid}.*"))
        if not candidates:
            raise FileNotFoundError(f"yt-dlp produced no file for {ytid}")
        raw_audio = candidates[0]
        ffmpeg_trim_to_wav(raw_audio, output_wav, start=start, end=end)
    info = sf.info(str(output_wav))
    if info.duration <= 0:
        raise RuntimeError(f"invalid output audio for {ytid}")
    return "downloaded"


def materialize_eval_set(limit_per_label: int | None = None) -> dict[str, object]:
    out_dir = AUDIOSET_ROOT / "clips" / "test"
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_dir = AUDIOSET_ROOT / "manifests"
    manifest_dir.mkdir(parents=True, exist_ok=True)

    df = build_eval_manifest(limit_per_label=limit_per_label)
    records: list[dict[str, object]] = []
    success = 0
    failed = 0

    for index, row in df.iterrows():
        ytid = row["ytid"]
        label = row["label"]
        audio_path = out_dir / f"yt_{ytid}.wav"
        status = "pending"
        try:
            status = download_youtube_audio(
                ytid=ytid,
                output_wav=audio_path,
                start=float(row["start_seconds"]),
                end=float(row["end_seconds"]),
            )
            success += 1
        except Exception as exc:
            failed += 1
            status = f"failed:{type(exc).__name__}"

        records.append(
            {
                "id": f"audioset_test_{label}_{index:04d}",
                "dataset": "audioset",
                "split": "test",
                "label": label,
                "ytid": ytid,
                "audio_path": str(audio_path),
                "exists": int(audio_path.exists()),
                "download_status": status,
                "duration_sec": float(row["clip_duration_sec"]),
                "window_start": float(row["window_start"]),
                "window_length": float(row["window_length"]),
                "Start": row["Start"],
                "End": row["End"],
                "Start.1": row["Start.1"],
                "End.1": row["End.1"],
                "Start.2": row["Start.2"],
                "End.2": row["End.2"],
                "Start.3": row["Start.3"],
                "End.3": row["End.3"],
                "Start.4": row["Start.4"],
                "End.4": row["End.4"],
            }
        )
        if (index + 1) % 25 == 0 or index + 1 == len(df):
            print(f"[materialize_audioset] processed {index + 1}/{len(df)}", flush=True)

    manifest_path = manifest_dir / "manifest_test_downloads.csv"
    pd.DataFrame.from_records(records).to_csv(manifest_path, index=False)
    summary = {
        "manifest": str(manifest_path),
        "requested": int(len(df)),
        "success": int(success),
        "failed": int(failed),
    }
    (manifest_dir / "materialize_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Download and materialize AudioSet evaluation clips locally.")
    parser.add_argument("--limit-per-label", type=int, default=None)
    args = parser.parse_args()
    summary = materialize_eval_set(limit_per_label=args.limit_per_label)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
