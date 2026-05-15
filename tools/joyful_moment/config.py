from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal


DetectionLevel = Literal["frequent", "medium", "sparse"]


@dataclass(frozen=True, slots=True)
class JoyfulMomentConfig:
    chunk_ms: int = 200
    clip_duration_s: int = 30
    context_neighbor_clips: int = 2
    event_window_s: int = 600
    trigger_video_duration_s: int = 5
    trigger_photo_count: int = 2
    detection_level: DetectionLevel = "medium"

    speechmatics_language: str = "en"
    speechmatics_operating_point: str = "enhanced"
    speechmatics_max_delay_s: float | None = None

    output_root: Path = Path("run_logs") / "joyful_moment"

    @classmethod
    def preset(cls, detection_level: DetectionLevel) -> "JoyfulMomentConfig":
        config = cls(detection_level=detection_level)
        if detection_level == "frequent":
            return cls(
                chunk_ms=200,
                clip_duration_s=20,
                context_neighbor_clips=3,
                event_window_s=480,
                trigger_video_duration_s=5,
                trigger_photo_count=2,
                detection_level=detection_level,
                speechmatics_language=config.speechmatics_language,
                speechmatics_operating_point=config.speechmatics_operating_point,
                speechmatics_max_delay_s=config.speechmatics_max_delay_s,
                output_root=config.output_root,
            )
        if detection_level == "sparse":
            return cls(
                chunk_ms=200,
                clip_duration_s=45,
                context_neighbor_clips=1,
                event_window_s=900,
                trigger_video_duration_s=5,
                trigger_photo_count=2,
                detection_level=detection_level,
                speechmatics_language=config.speechmatics_language,
                speechmatics_operating_point=config.speechmatics_operating_point,
                speechmatics_max_delay_s=config.speechmatics_max_delay_s,
                output_root=config.output_root,
            )
        return config

    @classmethod
    def from_json_path(cls, path: Path) -> "JoyfulMomentConfig":
        payload = json.loads(path.read_text(encoding="utf-8"))
        return cls.from_payload(payload, base_dir=path.parent)

    @classmethod
    def from_payload(cls, payload: dict[str, Any], *, base_dir: Path | None = None) -> "JoyfulMomentConfig":
        output_root = payload.get("output_root")
        if output_root is None:
            resolved_output_root = cls.output_root
        else:
            candidate = Path(str(output_root))
            resolved_output_root = candidate if candidate.is_absolute() else Path.cwd() / candidate

        detection_level = str(payload.get("detection_level", cls.detection_level))
        if detection_level in {"frequent", "medium", "sparse"}:
            config = cls.preset(detection_level)  # preset first, then allow payload override.
        else:
            config = cls()

        return cls(
            chunk_ms=int(payload.get("chunk_ms", config.chunk_ms)),
            clip_duration_s=int(payload.get("clip_duration_s", config.clip_duration_s)),
            context_neighbor_clips=int(payload.get("context_neighbor_clips", config.context_neighbor_clips)),
            event_window_s=int(payload.get("event_window_s", config.event_window_s)),
            trigger_video_duration_s=int(
                payload.get("trigger_video_duration_s", config.trigger_video_duration_s)
            ),
            trigger_photo_count=int(payload.get("trigger_photo_count", config.trigger_photo_count)),
            detection_level=detection_level,
            speechmatics_language=str(payload.get("speechmatics_language", config.speechmatics_language)),
            speechmatics_operating_point=str(
                payload.get("speechmatics_operating_point", config.speechmatics_operating_point)
            ),
            speechmatics_max_delay_s=payload.get("speechmatics_max_delay_s", config.speechmatics_max_delay_s),
            output_root=resolved_output_root,
        )
