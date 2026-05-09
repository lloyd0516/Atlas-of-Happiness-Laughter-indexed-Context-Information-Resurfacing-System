from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(slots=True)
class AudioFormat:
    type: str
    encoding: str
    sample_rate: int

    def to_payload(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class LaughterEvent:
    phase: str
    event_type: str
    start_time: float | None = None
    end_time: float | None = None
    confidence: float | None = None
    channel: str | None = None
    raw_message: str | None = None

    def to_payload(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class SpeechmaticsSessionConfig:
    language: str
    operating_point: str = "enhanced"
    event_types: list[str] = field(default_factory=lambda: ["laughter"])
    audio_format: AudioFormat = field(
        default_factory=lambda: AudioFormat(type="raw", encoding="pcm_s16le", sample_rate=16000)
    )
    max_delay: float | None = None
    enable_partials: bool = False

    def start_recognition_payload(self) -> dict[str, Any]:
        transcription_config: dict[str, Any] = {
            "language": self.language,
            "operating_point": self.operating_point,
            "enable_partials": self.enable_partials,
        }
        if self.max_delay is not None:
            transcription_config["max_delay"] = self.max_delay

        return {
            "message": "StartRecognition",
            "audio_format": self.audio_format.to_payload(),
            "transcription_config": transcription_config,
            "audio_events_config": {"types": self.event_types},
        }


@dataclass(slots=True)
class StreamInitRequest:
    sample_rate: int = 16000
    encoding: str = "pcm_s16le"
    language: str = "en"
    chunk_ms: int = 200
    channels: int = 1
    event_types: list[str] = field(default_factory=lambda: ["laughter"])

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "StreamInitRequest":
        return cls(
            sample_rate=int(payload.get("sample_rate", 16000)),
            encoding=str(payload.get("encoding", "pcm_s16le")),
            language=str(payload.get("language", "en")),
            chunk_ms=int(payload.get("chunk_ms", 200)),
            channels=int(payload.get("channels", 1)),
            event_types=list(payload.get("event_types", ["laughter"])),
        )
