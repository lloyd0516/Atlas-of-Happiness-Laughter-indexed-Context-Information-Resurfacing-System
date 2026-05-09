from __future__ import annotations

import json
from typing import Any
from uuid import uuid4

from .audio_sources import WavPcm16AudioSource
from .config import Settings
from .contracts import AudioFormat, LaughterEvent, SpeechmaticsSessionConfig, StreamInitRequest
from .speechmatics_client import SpeechmaticsRealtimeClient, parse_laughter_event


class RealtimeLaughterService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def process_wav_bytes(
        self,
        wav_bytes: bytes,
        source_name: str,
        chunk_ms: int = 200,
        pace_realtime: bool = True,
        language: str | None = None,
    ) -> dict[str, Any]:
        source = WavPcm16AudioSource(
            source_name=source_name,
            wav_bytes=wav_bytes,
            chunk_ms=chunk_ms,
            pace_realtime=pace_realtime,
        )
        session_config = SpeechmaticsSessionConfig(
            language=language or self.settings.speechmatics_language,
            operating_point=self.settings.speechmatics_operating_point,
            event_types=self.settings.speechmatics_event_types,
            audio_format=source.audio_format,
        )

        session_id = uuid4().hex
        events: list[LaughterEvent] = []
        messages: list[dict[str, Any]] = []

        async def _handle_message(payload: dict[str, Any]) -> None:
            messages.append(payload)
            event = parse_laughter_event(payload)
            if event is not None:
                events.append(event)

        async with SpeechmaticsRealtimeClient(
            url=self.settings.speechmatics_rt_url,
            api_key=self.settings.speechmatics_api_key,
        ) as client:
            recognition_started = await client.run_audio(
                config=session_config,
                chunk_iterator=source.iter_chunks(),
                message_handler=_handle_message,
            )

        return {
            "session_id": session_id,
            "source": source_name,
            "recognition_started": recognition_started,
            "events": [event.to_payload() for event in events],
            "message_count": len(messages),
        }

    def build_session_config_from_stream(self, request: StreamInitRequest) -> SpeechmaticsSessionConfig:
        if request.channels != 1:
            raise ValueError("Only mono PCM input is supported in the first demo version.")
        if request.encoding != "pcm_s16le":
            raise ValueError("Only pcm_s16le input is supported in the first demo version.")

        return SpeechmaticsSessionConfig(
            language=request.language or self.settings.speechmatics_language,
            operating_point=self.settings.speechmatics_operating_point,
            event_types=request.event_types or self.settings.speechmatics_event_types,
            audio_format=AudioFormat(
                type="raw",
                encoding=request.encoding,
                sample_rate=request.sample_rate,
            ),
        )

    def save_browser_session(
        self,
        *,
        session_id: str,
        started_at: str | None,
        source: str,
        language: str,
        events: list[dict[str, Any]],
    ) -> dict[str, Any]:
        self.settings.output_dir.mkdir(parents=True, exist_ok=True)
        output_path = self.settings.output_dir / f"{session_id}.json"
        payload = {
            "session_id": session_id,
            "started_at": started_at,
            "source": source,
            "language": language,
            "event_count": len(events),
            "events": events,
        }
        output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return {"saved": True, "path": str(output_path), "event_count": len(events)}
