from __future__ import annotations

import argparse
import json
import math
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .config import JoyfulMomentConfig


@dataclass
class ClipState:
    clip_id: int
    start_offset_s: float
    end_offset_s: float
    tmp_path: Path | None = None
    has_laughter: bool = False
    has_speech: bool = False
    detection_ids: list[str] = field(default_factory=list)
    related_laughter_clip_ids: list[int] = field(default_factory=list)
    saved_path: Path | None = None


def _overlaps(a_start: float, a_end: float, b_start: float, b_end: float) -> bool:
    return (a_start < b_end) and (b_start < a_end)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Rebuild period/event logs from an existing joyful_moment session.")
    parser.add_argument("--session-dir", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path("tools/joyful_moment/config.json"))
    parser.add_argument("--copy-clips", action="store_true", help="Copy instead of move tmp clips into labeled outputs.")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    config = JoyfulMomentConfig.from_json_path(args.config) if args.config.exists() else JoyfulMomentConfig()
    session_dir = args.session_dir
    detection_log = session_dir / "detection_log.jsonl"
    speech_raw = session_dir / "speechmatics_raw.jsonl"
    if not detection_log.exists():
        raise SystemExit(f"missing detection log: {detection_log}")

    clip_states: dict[int, ClipState] = {}
    detections: list[dict[str, Any]] = []
    speech_ranges = 0

    def ensure_clip(clip_id: int) -> ClipState:
        state = clip_states.get(clip_id)
        if state is None:
            start_offset_s = clip_id * config.clip_duration_s
            state = ClipState(
                clip_id=clip_id,
                start_offset_s=float(start_offset_s),
                end_offset_s=float(start_offset_s + config.clip_duration_s),
            )
            clip_states[clip_id] = state
        return state

    with detection_log.open("r", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            obj = json.loads(line)
            typ = obj.get("type")
            if typ in {"detection.laughter.ended", "detection.layer"}:
                det_id = obj.get("det_id")
                start = float(obj.get("start_offset_s") or 0.0)
                end = float(obj.get("end_offset_s") or start)
                if end < start:
                    end = start
                detections.append(
                    {
                        "det_id": det_id,
                        "start_offset_s": start,
                        "end_offset_s": end,
                        "confidence": obj.get("confidence"),
                        "channel": obj.get("channel"),
                    }
                )
                start_clip = int(start // config.clip_duration_s)
                end_clip = int(end // config.clip_duration_s)
                for clip_id in range(start_clip, end_clip + 1):
                    clip = ensure_clip(clip_id)
                    if _overlaps(start, end, clip.start_offset_s, clip.end_offset_s):
                        clip.has_laughter = True
                        if det_id is not None:
                            clip.detection_ids.append(str(det_id))
            elif typ == "detection.speech.range":
                speech_ranges += 1
                start = float(obj.get("start_offset_s") or 0.0)
                end = float(obj.get("end_offset_s") or start)
                start_clip = int(start // config.clip_duration_s)
                end_clip = int(end // config.clip_duration_s)
                for clip_id in range(start_clip, end_clip + 1):
                    clip = ensure_clip(clip_id)
                    if _overlaps(start, end, clip.start_offset_s, clip.end_offset_s):
                        clip.has_speech = True

    processed_duration_s = 0.0
    if speech_raw.exists():
        with speech_raw.open("r", encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                obj = json.loads(line)
                if obj.get("type") == "audio.chunk.sent":
                    processed_duration_s = max(processed_duration_s, float(obj.get("offset_s") or 0.0))
    if processed_duration_s > 0:
        max_clip_id = int(math.floor(processed_duration_s / config.clip_duration_s))
        for clip_id in range(max_clip_id + 1):
            ensure_clip(clip_id)

    tmp_dir = session_dir / "clips" / "_tmp"
    if tmp_dir.exists():
        for wav_path in sorted(tmp_dir.glob("clip_*.wav")):
            try:
                clip_id = int(wav_path.stem.split("_")[1])
            except Exception:
                continue
            ensure_clip(clip_id).tmp_path = wav_path

    laughter_clip_ids = sorted(clip_id for clip_id, state in clip_states.items() if state.has_laughter)

    def neighbor_laughter_ids(clip_id: int) -> list[int]:
        return [
            other_id
            for other_id in laughter_clip_ids
            if other_id != clip_id and abs(other_id - clip_id) <= config.context_neighbor_clips
        ]

    clips_dir = session_dir / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)
    period_records: list[dict[str, Any]] = []
    for clip_id in sorted(clip_states.keys()):
        clip = clip_states[clip_id]
        clip.related_laughter_clip_ids = neighbor_laughter_ids(clip_id)
        label = "none"
        if clip.has_laughter:
            label = "laughter"
        elif clip.has_speech and clip.related_laughter_clip_ids:
            label = "possible_related_speech_context"

        if label != "none" and clip.tmp_path is not None and clip.tmp_path.exists():
            clip.saved_path = clips_dir / f"clip_{clip_id:06d}_{label}.wav"
            if args.copy_clips:
                shutil.copy2(clip.tmp_path, clip.saved_path)
            else:
                if not clip.saved_path.exists():
                    shutil.move(str(clip.tmp_path), str(clip.saved_path))

        period_records.append(
            {
                "type": "period.layer",
                "period_id": f"period_{clip_id:06d}",
                "clip_id": clip_id,
                "start_offset_s": clip.start_offset_s,
                "end_offset_s": clip.end_offset_s,
                "label": label,
                "has_laughter": clip.has_laughter,
                "has_speech": clip.has_speech,
                "detection_ids": clip.detection_ids,
                "related_laughter_clip_ids": clip.related_laughter_clip_ids,
                "saved_path": str(clip.saved_path) if clip.saved_path is not None else None,
                "trigger": {
                    "prompt_user_note": label == "laughter",
                    "auto_video_capture": label == "laughter",
                    "auto_video_duration_s": config.trigger_video_duration_s if label == "laughter" else 0,
                    "auto_photo_count": config.trigger_photo_count if label == "laughter" else 0,
                },
                "assets": {"video": None, "photos": []},
            }
        )

    events: dict[int, dict[str, Any]] = {}
    for record in period_records:
        if record["label"] == "none":
            continue
        bucket_id = int(record["start_offset_s"] // config.event_window_s)
        event = events.get(bucket_id)
        if event is None:
            bucket_start = bucket_id * config.event_window_s
            event = {
                "type": "event.layer",
                "event_id": f"event_{bucket_id:04d}",
                "event_bucket_id": bucket_id,
                "start_offset_s": float(bucket_start),
                "end_offset_s": float(bucket_start + config.event_window_s),
                "period_ids": [],
                "laughter_period_ids": [],
                "context_period_ids": [],
                "context_clip_ids": [],
                "detection_ids": [],
                "saved_clip_paths": [],
                "assets": {"video": None, "photos": []},
            }
            events[bucket_id] = event
        event["period_ids"].append(record["period_id"])
        if record["label"] == "laughter":
            event["laughter_period_ids"].append(record["period_id"])
        elif record["label"] == "possible_related_speech_context":
            event["context_period_ids"].append(record["period_id"])
            event["context_clip_ids"].append(record["clip_id"])
        for det_id in record["detection_ids"]:
            if det_id not in event["detection_ids"]:
                event["detection_ids"].append(det_id)
        if record["saved_path"] is not None:
            event["saved_clip_paths"].append(record["saved_path"])

    (session_dir / "period_log.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in period_records), encoding="utf-8"
    )
    (session_dir / "event_log.jsonl").write_text(
        "".join(json.dumps(events[k], ensure_ascii=False) + "\n" for k in sorted(events.keys())), encoding="utf-8"
    )

    report = {
        "meta": {
            "session_dir": str(session_dir),
            "processed_duration_s": processed_duration_s,
            "reconstructed_from_existing_detection_log": True,
        },
        "stats": {
            "detection_count": len(detections),
            "speech_range_count": speech_ranges,
            "clip_count_total": len(clip_states),
            "clip_count_laughter": sum(1 for r in period_records if r["label"] == "laughter"),
            "clip_count_possible_context": sum(
                1 for r in period_records if r["label"] == "possible_related_speech_context"
            ),
            "event_count": len(events),
        },
    }
    (session_dir / "brief_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    summary = {
        "session_id": session_dir.name,
        "reconstructed": True,
        "paths": {
            "session_dir": str(session_dir),
            "detection_log": str(detection_log),
            "period_log": str(session_dir / "period_log.jsonl"),
            "event_log": str(session_dir / "event_log.jsonl"),
            "report_json": str(session_dir / "brief_report.json"),
        },
        "stats": report["stats"],
    }
    (session_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
