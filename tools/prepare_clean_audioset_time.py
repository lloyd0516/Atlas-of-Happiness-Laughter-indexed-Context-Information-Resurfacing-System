from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
AUDIOSET_ROOT = ROOT / "datasets_unified" / "audioset"
GILLICK_ANN = ROOT / "gillick" / "data" / "audioset" / "annotations"


def row_to_segments(row: pd.Series) -> list[dict[str, float]]:
    segments = []
    for suffix in ["", ".1", ".2", ".3", ".4"]:
        start_key = f"Start{suffix}"
        end_key = f"End{suffix}"
        start = row.get(start_key)
        end = row.get(end_key)
        if pd.notna(start) and pd.notna(end):
            start_f = float(start)
            end_f = float(end)
            segments.append({"start": start_f, "end": end_f, "duration": max(0.0, end_f - start_f)})
    return segments


def build_manifest() -> dict[str, object]:
    manifest_path = AUDIOSET_ROOT / "manifest_test.csv"
    if not manifest_path.exists():
        raise FileNotFoundError(f"Missing {manifest_path}")
    df = pd.read_csv(manifest_path)
    laughter_df = pd.read_csv(GILLICK_ANN / "clean_laughter_annotations.csv")
    distractor_df = pd.read_csv(GILLICK_ANN / "clean_distractor_annotations.csv")
    laughter_map = {row["FileID"]: row for _, row in laughter_df.iterrows()}
    distractor_map = {row["FileID"]: row for _, row in distractor_df.iterrows()}
    out_dir = AUDIOSET_ROOT / "annotations_clean"
    out_dir.mkdir(parents=True, exist_ok=True)

    records = []
    for _, row in df.iterrows():
        ytid = row["ytid"]
        if row["label"] == "laughter":
            ann_row = laughter_map[ytid]
            segments = row_to_segments(ann_row)
        else:
            ann_row = distractor_map[ytid]
            segments = []
        annotation_path = out_dir / f"{ytid}.laughter.json"
        annotation_path.write_text(
            json.dumps(
                {
                    "ytid": ytid,
                    "audio_path": row["audio_path"],
                    "duration_sec": float(row["duration_sec"]),
                    "segments": segments,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        records.append(
            {
                "id": row["id"],
                "dataset": "audioset_clean",
                "split": row["split"],
                "label": row["label"],
                "audio_path": row["audio_path"],
                "duration_sec": float(row["duration_sec"]),
                "annotation_path": str(annotation_path),
            }
        )

    out_manifest = AUDIOSET_ROOT / "manifest_time_test.csv"
    with out_manifest.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(records[0].keys()))
        writer.writeheader()
        writer.writerows(records)
    return {"manifest": str(out_manifest), "num_rows": len(records)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Gillick clean AudioSet timestamp manifest.")
    parser.parse_args()
    print(json.dumps(build_manifest(), indent=2))


if __name__ == "__main__":
    main()
