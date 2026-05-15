from __future__ import annotations

import argparse
import csv
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1]
AMI_ROOT = ROOT / "datasets_unified" / "ami"
ANNOTATION_ROOT = AMI_ROOT / "manual_annotations"
SIGNAL_ROOT = AMI_ROOT / "signals" / "Mix-Headset"

SC_BASES = ["ES2004", "ES2014", "IS1009", "TS3003", "TS3007"]
SC_MEETINGS = [f"{base}{suffix}" for base in SC_BASES for suffix in "abcd"]
NITE_ID = "{http://nite.sourceforge.net/}id"


def merge_segments(segments: list[dict[str, float]], max_gap: float = 0.2) -> list[dict[str, float]]:
    if not segments:
        return []
    ordered = sorted(segments, key=lambda item: (item["start"], item["end"]))
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


def extract_laughter_segments(meeting_id: str) -> list[dict[str, float]]:
    segments: list[dict[str, float]] = []
    for speaker in "ABCD":
        path = ANNOTATION_ROOT / "words" / f"{meeting_id}.{speaker}.words.xml"
        if not path.exists():
            continue
        root = ET.parse(path).getroot()
        for child in root:
            if child.tag.endswith("vocalsound") and child.attrib.get("type") == "laugh":
                start = float(child.attrib["starttime"])
                end = float(child.attrib["endtime"])
                segments.append(
                    {
                        "start": start,
                        "end": end,
                        "duration": max(0.0, end - start),
                        "speaker": speaker,
                        "annotation_id": child.attrib.get(NITE_ID, ""),
                    }
                )
    return merge_segments(segments, max_gap=0.1)


def download_mix_headset(meeting_id: str) -> Path:
    SIGNAL_ROOT.mkdir(parents=True, exist_ok=True)
    out_path = SIGNAL_ROOT / f"{meeting_id}.Mix-Headset.wav"
    if out_path.exists():
        return out_path
    url = f"https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/{meeting_id}/audio/{meeting_id}.Mix-Headset.wav"
    cmd = ["curl", "-L", "--fail", "-C", "-", "-o", str(out_path), url]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out_path


def ensure_annotations_present() -> None:
    archive = AMI_ROOT / "raw" / "ami_public_manual_1.6.2.zip"
    if not archive.exists():
        raise FileNotFoundError(f"Missing {archive}")
    if (ANNOTATION_ROOT / "words").exists():
        return
    ANNOTATION_ROOT.mkdir(parents=True, exist_ok=True)
    subprocess.run(["unzip", "-q", "-o", str(archive), "-d", str(ANNOTATION_ROOT)], check=True)


def build_manifest(limit: int | None = None) -> dict[str, object]:
    ensure_annotations_present()
    selected = SC_MEETINGS[:limit] if limit else SC_MEETINGS
    annotation_dir = AMI_ROOT / "annotations_sc"
    annotation_dir.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    for index, meeting_id in enumerate(selected, start=1):
        audio_path = download_mix_headset(meeting_id)
        info = sf.info(str(audio_path))
        laughter_segments = extract_laughter_segments(meeting_id)
        annotation_path = annotation_dir / f"{meeting_id}.laughter.json"
        annotation_path.write_text(
            json.dumps(
                {
                    "meeting_id": meeting_id,
                    "audio_path": str(audio_path),
                    "duration_sec": float(info.duration),
                    "segments": laughter_segments,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        records.append(
            {
                "id": meeting_id,
                "dataset": "ami_sc",
                "split": "sc_eval",
                "label": "meeting",
                "audio_path": str(audio_path),
                "duration_sec": float(info.duration),
                "annotation_path": str(annotation_path),
                "num_laughter_segments": len(laughter_segments),
            }
        )
        print(f"[prepare_ami_sc] processed {index}/{len(selected)} {meeting_id}", flush=True)

    manifest_path = AMI_ROOT / "manifest_sc.csv"
    with manifest_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(records[0].keys()))
        writer.writeheader()
        writer.writerows(records)
    return {"manifest": str(manifest_path), "num_meetings": len(records)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare AMI SC timestamp benchmark assets.")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()
    summary = build_manifest(limit=args.limit)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
