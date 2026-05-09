from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _split_csv(value: str | None, default: list[str]) -> list[str]:
    if not value:
        return default
    return [item.strip() for item in value.split(",") if item.strip()]


@dataclass(slots=True)
class Settings:
    speechmatics_api_key: str
    speechmatics_rt_url: str = "wss://eu2.rt.speechmatics.com/v2"
    speechmatics_language: str = "en"
    speechmatics_operating_point: str = "enhanced"
    speechmatics_event_types: list[str] = field(default_factory=lambda: ["laughter"])
    app_host: str = "127.0.0.1"
    app_port: int = 8010
    output_dir: Path = Path("speechmatics_demo/output")

    @classmethod
    def from_env(cls) -> "Settings":
        api_key = os.getenv("SPEECHMATICS_API_KEY", "").strip()
        if not api_key:
            raise RuntimeError("Missing SPEECHMATICS_API_KEY environment variable.")
        return cls(
            speechmatics_api_key=api_key,
            speechmatics_rt_url=os.getenv("SPEECHMATICS_RT_URL", "wss://eu2.rt.speechmatics.com/v2").strip(),
            speechmatics_language=os.getenv("SPEECHMATICS_LANGUAGE", "en").strip(),
            speechmatics_operating_point=os.getenv("SPEECHMATICS_OPERATING_POINT", "enhanced").strip(),
            speechmatics_event_types=_split_csv(os.getenv("SPEECHMATICS_EVENT_TYPES"), ["laughter"]),
            app_host=os.getenv("APP_HOST", "127.0.0.1").strip(),
            app_port=int(os.getenv("APP_PORT", "8010")),
            output_dir=Path(os.getenv("SPEECHMATICS_OUTPUT_DIR", "speechmatics_demo/output")),
        )
