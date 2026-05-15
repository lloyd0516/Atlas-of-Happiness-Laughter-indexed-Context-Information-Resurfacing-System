from __future__ import annotations

import argparse
import csv
import io
import json
import re
from pathlib import Path

import soundfile as sf
import pyarrow.parquet as pq


ROOT = Path(__file__).resolve().parents[1]
DATASETS_ROOT = ROOT / "datasets_unified"


def classify_switchboard_transcript(transcript: str) -> str:
    tokens = transcript.split()
    has_laugh_token = any(token.upper() in {"[LAUGH]", "<LAUGH>"} for token in tokens)
    has_speechlaugh = any(token.isupper() and token.upper() not in {"[LAUGH]", "<LAUGH>"} for token in tokens)
    if has_laugh_token:
        return "laughter"
    if has_speechlaugh:
        return "speechlaugh"
    return "speech"


def safe_slug(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]+", "_", value).strip("_")


def prepare_switchboard(args: argparse.Namespace) -> dict:
    out_dir = DATASETS_ROOT / "switchboard"
    audio_dir = out_dir / "clips" / args.split
    raw_dir = out_dir / "raw"
    audio_dir.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / f"manifest_{args.split}.csv"
    parquet_files = sorted(raw_dir.glob(f"{args.split}-*.parquet"))
    if not parquet_files:
        raise FileNotFoundError(
            f"No local parquet files found under {raw_dir}. Download at least one {args.split} parquet shard first."
        )

    per_label_limit = {
        "laughter": args.max_laughter,
        "speechlaugh": args.max_speechlaugh,
        "speech": args.max_speech,
    }
    counts = {label: 0 for label in per_label_limit}
    rows: list[dict[str, object]] = []

    global_index = 0
    for parquet_path in parquet_files:
        table = pq.read_table(parquet_path)
        for example in table.to_pylist():
            transcript = str(example["transcript"]).strip()
            label = classify_switchboard_transcript(transcript)
            if counts[label] >= per_label_limit[label]:
                if all(counts[name] >= per_label_limit[name] for name in per_label_limit):
                    break
                continue

            audio_blob = example["audio"]
            audio_bytes = audio_blob["bytes"]
            audio_array, sample_rate = sf.read(io.BytesIO(audio_bytes), dtype="float32", always_2d=False)
            if getattr(audio_array, "ndim", 1) > 1:
                audio_array = audio_array.mean(axis=1 if audio_array.shape[0] < audio_array.shape[1] else 0)

            file_stem = safe_slug(f"{args.split}_{label}_{global_index:06d}")
            file_path = audio_dir / f"{file_stem}.wav"
            sf.write(file_path, audio_array, sample_rate)
            duration = len(audio_array) / float(sample_rate)

            rows.append(
                {
                    "id": file_stem,
                    "dataset": "switchboard",
                    "split": args.split,
                    "label": label,
                    "audio_path": str(file_path),
                    "duration_sec": round(duration, 4),
                    "transcript": transcript,
                }
            )
            counts[label] += 1
            global_index += 1

        if all(counts[name] >= per_label_limit[name] for name in per_label_limit):
            break

    with manifest_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["id", "dataset", "split", "label", "audio_path", "duration_sec", "transcript"],
        )
        writer.writeheader()
        writer.writerows(rows)

    readme = out_dir / "README.md"
    readme.write_text(
        "\n".join(
            [
                "# Switchboard Benchmark Subset",
                "",
                "- Source: `hhoangphuoc/switchboard` on Hugging Face.",
                "- Sampling rate: 16 kHz.",
                "- Labels used here:",
                "  - `laughter`: transcript contains `[LAUGH]` or `<LAUGH>`.",
                "  - `speechlaugh`: transcript contains uppercase speech-laugh tokens.",
                "  - `speech`: transcript contains neither laughter marker.",
                f"- Local subset split: `{args.split}`.",
                f"- Raw parquet source directory: `{raw_dir.relative_to(ROOT)}`.",
                f"- Requested sample caps: laughter={args.max_laughter}, speechlaugh={args.max_speechlaugh}, speech={args.max_speech}.",
                f"- Manifest: `{manifest_path.relative_to(ROOT)}`.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    return {
        "dataset": "switchboard",
        "manifest": str(manifest_path),
        "counts": counts,
        "num_rows": len(rows),
    }


def write_dataset_notes() -> None:
    datasets = {
        "audioset": [
            "# AudioSet Notes",
            "",
            "- This workspace already contains IDEO metadata and balanced TFRecord subsets in `ideo/Data`.",
            "- Raw 10-second audio clips are not bundled in the repository.",
            "- To benchmark all four detectors on AudioSet fairly, raw audio clips must be downloaded separately from the original AudioSet / YouTube sources.",
        ],
        "ami": [
            "# AMI Notes",
            "",
            "- AMI laughter/disfluency variants are not bundled locally under `datasets_unified`.",
            "- The accessible Hugging Face AMI variants commonly expose speech transcripts, but the richer disfluency/laughter resources may require gated access or extra preprocessing.",
            "- This pipeline keeps the dataset folder and documentation ready; benchmark execution is limited to datasets with locally materialized audio clips.",
        ],
    }
    for name, lines in datasets.items():
        out_dir = DATASETS_ROOT / name
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare unified benchmark datasets.")
    parser.add_argument("--split", default="test")
    parser.add_argument("--max-laughter", type=int, default=120)
    parser.add_argument("--max-speechlaugh", type=int, default=120)
    parser.add_argument("--max-speech", type=int, default=240)
    args = parser.parse_args()

    DATASETS_ROOT.mkdir(parents=True, exist_ok=True)
    summary = {
        "switchboard": prepare_switchboard(args),
    }
    write_dataset_notes()
    (DATASETS_ROOT / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
