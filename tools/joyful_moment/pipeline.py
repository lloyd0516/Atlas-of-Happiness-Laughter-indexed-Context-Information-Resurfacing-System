from __future__ import annotations

import asyncio
import json
import os
import shutil
import wave
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import uuid4

from speechmatics_demo.app.audio_sources import AudioSourceError
from speechmatics_demo.app.contracts import LaughterEvent, SpeechmaticsSessionConfig
from speechmatics_demo.app.speechmatics_client import SpeechmaticsRealtimeClient, parse_laughter_event

from .config import JoyfulMomentConfig
from .logging_utils import JsonlWriter


def _now_local() -> datetime:
    return datetime.now().astimezone()


def _device_time(session_started_at: datetime, offset_s: float) -> datetime:
    return session_started_at + timedelta(seconds=float(offset_s))


def _overlaps(a_start: float, a_end: float, b_start: float, b_end: float) -> bool:
    return (a_start < b_end) and (b_start < a_end)


def _extract_speech_time_ranges(payload: dict[str, Any]) -> list[tuple[float, float]]:
    message_type = payload.get("message")
    if not isinstance(message_type, str) or "Transcript" not in message_type:
        return []

    ranges: list[tuple[float, float]] = []
    for result in payload.get("results", []) or []:
        if not isinstance(result, dict):
            continue
        start = result.get("start_time")
        end = result.get("end_time")
        if isinstance(start, (int, float)) and isinstance(end, (int, float)) and end >= start:
            ranges.append((float(start), float(end)))
    start = payload.get("start_time")
    end = payload.get("end_time")
    if isinstance(start, (int, float)) and isinstance(end, (int, float)) and end >= start:
        ranges.append((float(start), float(end)))
    return ranges


@dataclass(slots=True)
class DetectionRecord:
    det_id: str
    start_offset_s: float
    end_offset_s: float
    confidence: float | None
    channel: str | None
    speechmatics_started_message: dict[str, Any] | None = None
    speechmatics_ended_message: dict[str, Any] | None = None

    def to_payload(self, *, session_started_at: datetime) -> dict[str, Any]:
        return {
            "type": "detection.layer",
            "det_id": self.det_id,
            "device_start_ts": _device_time(session_started_at, self.start_offset_s).isoformat(),
            "device_end_ts": _device_time(session_started_at, self.end_offset_s).isoformat(),
            "start_offset_s": self.start_offset_s,
            "end_offset_s": self.end_offset_s,
            "duration_s": max(0.0, self.end_offset_s - self.start_offset_s),
            "confidence": self.confidence,
            "channel": self.channel,
        }


@dataclass(slots=True)
class ClipState:
    clip_id: int
    start_offset_s: float
    end_offset_s: float
    tmp_path: Path
    has_laughter: bool = False
    has_speech: bool = False
    finalized: bool = False
    detection_ids: list[str] = field(default_factory=list)
    laughter_events: list[dict[str, Any]] = field(default_factory=list)
    related_laughter_clip_ids: list[int] = field(default_factory=list)
    period_id: str | None = None
    period_label: str = "none"
    saved_path: Path | None = None
    parent_event_id: str | None = None
    prompt_triggered: bool = False
    auto_video_requested: bool = False
    auto_photo_count: int = 0


@dataclass(frozen=True, slots=True)
class SessionPaths:
    session_dir: Path
    detection_log: Path
    period_log: Path
    event_log: Path
    summary_json: Path
    speechmatics_raw_jsonl: Path
    report_json: Path
    report_md: Path


def _make_session_paths(root: Path, session_id: str) -> SessionPaths:
    session_dir = root / session_id
    return SessionPaths(
        session_dir=session_dir,
        detection_log=session_dir / "detection_log.jsonl",
        period_log=session_dir / "period_log.jsonl",
        event_log=session_dir / "event_log.jsonl",
        summary_json=session_dir / "summary.json",
        speechmatics_raw_jsonl=session_dir / "speechmatics_raw.jsonl",
        report_json=session_dir / "brief_report.json",
        report_md=session_dir / "brief_report.md",
    )


class WavClipper:
    def __init__(self, *, session_dir: Path, sample_rate: int, channels: int, sample_width: int, clip_duration_s: int):
        self.session_dir = session_dir
        self.sample_rate = sample_rate
        self.channels = channels
        self.sample_width = sample_width
        self.clip_duration_s = clip_duration_s

        self.tmp_dir = session_dir / "clips" / "_tmp"
        self.tmp_dir.mkdir(parents=True, exist_ok=True)

        self._frames_per_clip = int(self.sample_rate * self.clip_duration_s)
        self._clip_id = 0
        self._frames_in_clip = 0
        self._writer: wave.Wave_write | None = None
        self._current_path: Path | None = None

    def _open_new(self) -> None:
        if self._writer is not None:
            self._writer.close()
        path = self.tmp_dir / f"clip_{self._clip_id:06d}.wav"
        writer = wave.open(str(path), "wb")
        writer.setnchannels(self.channels)
        writer.setsampwidth(self.sample_width)
        writer.setframerate(self.sample_rate)
        self._writer = writer
        self._current_path = path
        self._frames_in_clip = 0

    def write_frames(self, pcm_bytes: bytes) -> list[ClipState]:
        if self._writer is None:
            self._open_new()

        bytes_per_frame = self.channels * self.sample_width
        if len(pcm_bytes) % bytes_per_frame != 0:
            raise ValueError("PCM bytes are not aligned to frame boundaries.")

        total_frames = len(pcm_bytes) // bytes_per_frame
        closed: list[ClipState] = []
        offset = 0

        while total_frames > 0:
            remaining = self._frames_per_clip - self._frames_in_clip
            take_frames = min(remaining, total_frames)
            take_bytes = take_frames * bytes_per_frame
            chunk = pcm_bytes[offset : offset + take_bytes]

            assert self._writer is not None
            self._writer.writeframesraw(chunk)
            self._frames_in_clip += take_frames
            total_frames -= take_frames
            offset += take_bytes

            if self._frames_in_clip >= self._frames_per_clip:
                self._writer.close()
                assert self._current_path is not None
                start_offset_s = self._clip_id * self.clip_duration_s
                end_offset_s = start_offset_s + self.clip_duration_s
                closed.append(
                    ClipState(
                        clip_id=self._clip_id,
                        start_offset_s=float(start_offset_s),
                        end_offset_s=float(end_offset_s),
                        tmp_path=self._current_path,
                    )
                )
                self._clip_id += 1
                self._writer = None
                self._current_path = None
                self._frames_in_clip = 0
                if total_frames > 0:
                    self._open_new()

        return closed

    def finalize_partial(self) -> ClipState | None:
        if self._writer is None or self._frames_in_clip == 0:
            return None
        self._writer.close()
        assert self._current_path is not None
        start_offset_s = self._clip_id * self.clip_duration_s
        end_offset_s = start_offset_s + (self._frames_in_clip / self.sample_rate)
        state = ClipState(
            clip_id=self._clip_id,
            start_offset_s=float(start_offset_s),
            end_offset_s=float(end_offset_s),
            tmp_path=self._current_path,
        )
        self._clip_id += 1
        self._writer = None
        self._current_path = None
        self._frames_in_clip = 0
        return state


class JoyfulMomentPipeline:
    def __init__(self, config: JoyfulMomentConfig) -> None:
        self.config = config

    def _speechmatics_env(self) -> tuple[str, str]:
        api_key = os.environ.get("SPEECHMATICS_API_KEY")
        rt_url = os.environ.get("SPEECHMATICS_RT_URL")
        if not api_key or not rt_url:
            raise RuntimeError("Missing SPEECHMATICS_API_KEY / SPEECHMATICS_RT_URL in environment.")
        return rt_url, api_key

    async def run_wav_path(self, wav_path: Path, *, pace_realtime: bool, max_seconds: float | None = None) -> dict[str, Any]:
        session_id = uuid4().hex
        paths = _make_session_paths(self.config.output_root, session_id)
        paths.session_dir.mkdir(parents=True, exist_ok=True)

        session_started_at = _now_local()
        rt_url, api_key = self._speechmatics_env()

        wav = wave.open(str(wav_path), "rb")
        try:
            channels = wav.getnchannels()
            sample_width = wav.getsampwidth()
            sample_rate = wav.getframerate()
            if channels != 1 or sample_width != 2:
                raise AudioSourceError("Expected mono 16-bit PCM WAV for this demo pipeline.")

            bytes_per_frame = channels * sample_width
            frames_per_chunk = int(sample_rate * (self.config.chunk_ms / 1000.0))

            clipper = WavClipper(
                session_dir=paths.session_dir,
                sample_rate=sample_rate,
                channels=channels,
                sample_width=sample_width,
                clip_duration_s=self.config.clip_duration_s,
            )

            clip_states: dict[int, ClipState] = {}
            speech_ranges: list[tuple[float, float]] = []
            detection_records: list[DetectionRecord] = []
            active_laughter: dict[str, dict[str, Any]] = {}
            next_det_idx = 0

            def _ensure_clip_state(clip_id: int) -> ClipState:
                state = clip_states.get(clip_id)
                if state is None:
                    start_offset_s = clip_id * self.config.clip_duration_s
                    state = ClipState(
                        clip_id=clip_id,
                        start_offset_s=float(start_offset_s),
                        end_offset_s=float(start_offset_s + self.config.clip_duration_s),
                        tmp_path=paths.session_dir / "clips" / "_tmp" / f"clip_{clip_id:06d}.wav",
                    )
                    clip_states[clip_id] = state
                return state

            def _mark_detection(record: DetectionRecord) -> None:
                start_clip = int(record.start_offset_s // self.config.clip_duration_s)
                end_clip = int(record.end_offset_s // self.config.clip_duration_s)
                for clip_id in range(start_clip, end_clip + 1):
                    clip = _ensure_clip_state(clip_id)
                    if _overlaps(record.start_offset_s, record.end_offset_s, clip.start_offset_s, clip.end_offset_s):
                        clip.has_laughter = True
                        clip.detection_ids.append(record.det_id)
                        clip.laughter_events.append(record.to_payload(session_started_at=session_started_at))

            def _mark_speech_range(start: float, end: float) -> None:
                if end < start:
                    return
                start_clip = int(start // self.config.clip_duration_s)
                end_clip = int(end // self.config.clip_duration_s)
                for clip_id in range(start_clip, end_clip + 1):
                    clip = _ensure_clip_state(clip_id)
                    if _overlaps(start, end, clip.start_offset_s, clip.end_offset_s):
                        clip.has_speech = True

            async def _message_handler(payload: dict[str, Any], det_writer: JsonlWriter) -> None:
                nonlocal next_det_idx

                message_type = payload.get("message")
                det_writer.write(
                    {
                        "type": "speechmatics.message",
                        "device_ts": _now_local().isoformat(),
                        "speechmatics_message": message_type,
                        "payload": payload,
                    }
                )

                parsed_event = parse_laughter_event(payload)
                if parsed_event is not None:
                    channel_key = str(parsed_event.channel or "default")
                    if parsed_event.phase == "started":
                        next_det_idx += 1
                        active_laughter[channel_key] = {
                            "det_id": f"det_{next_det_idx:06d}",
                            "start_offset_s": float(parsed_event.start_time or 0.0),
                            "confidence": parsed_event.confidence,
                            "started_message": payload,
                        }
                        det_writer.write(
                            {
                                "type": "detection.edge.started",
                                "det_id": active_laughter[channel_key]["det_id"],
                                "device_ts": _device_time(
                                    session_started_at, active_laughter[channel_key]["start_offset_s"]
                                ).isoformat(),
                                "start_offset_s": active_laughter[channel_key]["start_offset_s"],
                                "confidence": parsed_event.confidence,
                                "channel": parsed_event.channel,
                            }
                        )
                    else:
                        current = active_laughter.pop(channel_key, None)
                        if current is None:
                            next_det_idx += 1
                            current = {
                                "det_id": f"det_{next_det_idx:06d}",
                                "start_offset_s": float(parsed_event.start_time or parsed_event.end_time or 0.0),
                                "confidence": parsed_event.confidence,
                                "started_message": None,
                            }
                        record = DetectionRecord(
                            det_id=str(current["det_id"]),
                            start_offset_s=float(current["start_offset_s"]),
                            end_offset_s=float(parsed_event.end_time or current["start_offset_s"]),
                            confidence=parsed_event.confidence or current["confidence"],
                            channel=parsed_event.channel,
                            speechmatics_started_message=current["started_message"],
                            speechmatics_ended_message=payload,
                        )
                        if record.end_offset_s < record.start_offset_s:
                            record.end_offset_s = record.start_offset_s
                        detection_records.append(record)
                        _mark_detection(record)
                        det_writer.write(record.to_payload(session_started_at=session_started_at))

                for speech_start, speech_end in _extract_speech_time_ranges(payload):
                    speech_ranges.append((speech_start, speech_end))
                    _mark_speech_range(speech_start, speech_end)
                    det_writer.write(
                        {
                            "type": "detection.speech.range",
                            "device_ts": _device_time(session_started_at, speech_start).isoformat(),
                            "start_offset_s": speech_start,
                            "end_offset_s": speech_end,
                        }
                    )

            async def _chunk_iterator() -> Any:
                audio_offset_s = 0.0
                while True:
                    if max_seconds is not None and audio_offset_s >= max_seconds:
                        break
                    raw = wav.readframes(frames_per_chunk)
                    if not raw:
                        break
                    if max_seconds is not None:
                        remaining_s = max_seconds - audio_offset_s
                        if remaining_s <= 0:
                            break
                        max_bytes = int(remaining_s * sample_rate) * sample_width
                        if 0 < max_bytes < len(raw):
                            raw = raw[:max_bytes]
                            raw = raw[: len(raw) - (len(raw) % bytes_per_frame)]
                            if not raw:
                                break
                    yield raw, audio_offset_s
                    audio_offset_s += len(raw) / (sample_rate * sample_width)
                    if pace_realtime:
                        await asyncio.sleep(self.config.chunk_ms / 1000.0)

            async with SpeechmaticsRealtimeClient(url=rt_url, api_key=api_key) as client:
                session_config = SpeechmaticsSessionConfig(
                    language=self.config.speechmatics_language,
                    operating_point=self.config.speechmatics_operating_point,
                    event_types=["laughter"],
                    max_delay=self.config.speechmatics_max_delay_s,
                    enable_partials=False,
                )
                with JsonlWriter(paths.detection_log) as det_log, JsonlWriter(paths.speechmatics_raw_jsonl) as raw_log:
                    await client.start_recognition(session_config)

                    async def _producer() -> None:
                        async for raw, offset_s in _chunk_iterator():
                            raw_log.write(
                                {
                                    "type": "audio.chunk.sent",
                                    "device_ts": _device_time(session_started_at, offset_s).isoformat(),
                                    "offset_s": offset_s,
                                    "byte_len": len(raw),
                                }
                            )
                            await client.send_audio_chunk(raw)
                            for closed in clipper.write_frames(raw):
                                existing = clip_states.get(closed.clip_id)
                                if existing is None:
                                    clip_states[closed.clip_id] = closed
                                else:
                                    existing.tmp_path = closed.tmp_path
                                    existing.start_offset_s = closed.start_offset_s
                                    existing.end_offset_s = closed.end_offset_s
                        partial = clipper.finalize_partial()
                        if partial is not None:
                            existing = clip_states.get(partial.clip_id)
                            if existing is None:
                                clip_states[partial.clip_id] = partial
                            else:
                                existing.tmp_path = partial.tmp_path
                                existing.start_offset_s = partial.start_offset_s
                                existing.end_offset_s = partial.end_offset_s
                        await client.end_stream()

                    async def _consumer() -> None:
                        async for payload in client.iter_messages():
                            await _message_handler(payload, det_log)

                    producer = asyncio.create_task(_producer())
                    consumer = asyncio.create_task(_consumer())
                    done, pending = await asyncio.wait({producer, consumer}, return_when=asyncio.FIRST_EXCEPTION)
                    for task in pending:
                        task.cancel()
                    for task in done:
                        exc = task.exception()
                        if exc is not None:
                            raise exc
                    if pending:
                        await asyncio.gather(*pending, return_exceptions=True)

            for channel_key, current in list(active_laughter.items()):
                record = DetectionRecord(
                    det_id=str(current["det_id"]),
                    start_offset_s=float(current["start_offset_s"]),
                    end_offset_s=float(current["start_offset_s"]),
                    confidence=current["confidence"],
                    channel=None if channel_key == "default" else channel_key,
                    speechmatics_started_message=current["started_message"],
                    speechmatics_ended_message=None,
                )
                detection_records.append(record)
                _mark_detection(record)

            clips_dir = paths.session_dir / "clips"
            clips_dir.mkdir(parents=True, exist_ok=True)

            laughter_clip_ids = sorted(clip_id for clip_id, state in clip_states.items() if state.has_laughter)
            period_records: list[dict[str, Any]] = []

            def _neighbor_laughter_ids(clip_id: int) -> list[int]:
                related: list[int] = []
                for other_id in laughter_clip_ids:
                    if other_id == clip_id:
                        continue
                    if abs(other_id - clip_id) <= self.config.context_neighbor_clips:
                        related.append(other_id)
                return related

            with JsonlWriter(paths.period_log) as period_log:
                for clip_id in sorted(clip_states.keys()):
                    state = clip_states[clip_id]
                    state.period_id = f"period_{clip_id:06d}"
                    state.related_laughter_clip_ids = _neighbor_laughter_ids(clip_id)

                    if state.has_laughter:
                        state.period_label = "laughter"
                        state.prompt_triggered = True
                        state.auto_video_requested = True
                        state.auto_photo_count = self.config.trigger_photo_count
                    elif state.has_speech and state.related_laughter_clip_ids:
                        state.period_label = "possible_related_speech_context"
                    else:
                        state.period_label = "none"

                    if state.period_label != "none":
                        state.saved_path = clips_dir / f"clip_{clip_id:06d}_{state.period_label}.wav"
                        if state.tmp_path.exists():
                            shutil.move(str(state.tmp_path), str(state.saved_path))
                    elif state.tmp_path.exists():
                        state.tmp_path.unlink()

                    record = {
                        "type": "period.layer",
                        "period_id": state.period_id,
                        "clip_id": clip_id,
                        "device_start_ts": _device_time(session_started_at, state.start_offset_s).isoformat(),
                        "device_end_ts": _device_time(session_started_at, state.end_offset_s).isoformat(),
                        "start_offset_s": state.start_offset_s,
                        "end_offset_s": state.end_offset_s,
                        "label": state.period_label,
                        "has_laughter": state.has_laughter,
                        "has_speech": state.has_speech,
                        "detection_ids": state.detection_ids,
                        "related_laughter_clip_ids": state.related_laughter_clip_ids,
                        "saved": state.saved_path is not None,
                        "saved_path": str(state.saved_path) if state.saved_path is not None else None,
                        "trigger": {
                            "prompt_user_note": state.prompt_triggered,
                            "auto_video_capture": state.auto_video_requested,
                            "auto_video_duration_s": self.config.trigger_video_duration_s
                            if state.auto_video_requested
                            else 0,
                            "auto_photo_count": state.auto_photo_count,
                        },
                        "assets": {
                            "video": None,
                            "photos": [],
                        },
                    }
                    period_records.append(record)
                    period_log.write(record)

            events: dict[int, dict[str, Any]] = {}
            for state in clip_states.values():
                if state.period_label == "none":
                    continue
                bucket_id = int(state.start_offset_s // self.config.event_window_s)
                event = events.get(bucket_id)
                if event is None:
                    bucket_start = bucket_id * self.config.event_window_s
                    event = {
                        "type": "event.layer",
                        "event_id": f"event_{bucket_id:04d}",
                        "event_bucket_id": bucket_id,
                        "device_start_ts": _device_time(session_started_at, bucket_start).isoformat(),
                        "device_end_ts": _device_time(
                            session_started_at, bucket_start + self.config.event_window_s
                        ).isoformat(),
                        "start_offset_s": float(bucket_start),
                        "end_offset_s": float(bucket_start + self.config.event_window_s),
                        "period_ids": [],
                        "laughter_period_ids": [],
                        "context_period_ids": [],
                        "context_clip_ids": [],
                        "detection_ids": [],
                        "saved_clip_paths": [],
                        "assets": {
                            "video": None,
                            "photos": [],
                        },
                    }
                    events[bucket_id] = event

                assert state.period_id is not None
                state.parent_event_id = event["event_id"]
                event["period_ids"].append(state.period_id)
                event["saved_clip_paths"].append(str(state.saved_path) if state.saved_path is not None else None)
                for det_id in state.detection_ids:
                    if det_id not in event["detection_ids"]:
                        event["detection_ids"].append(det_id)
                if state.period_label == "laughter":
                    event["laughter_period_ids"].append(state.period_id)
                elif state.period_label == "possible_related_speech_context":
                    event["context_period_ids"].append(state.period_id)
                    event["context_clip_ids"].append(state.clip_id)

            with JsonlWriter(paths.event_log) as event_log:
                for bucket_id in sorted(events.keys()):
                    event_log.write(events[bucket_id])

            report = self._build_report(
                wav_path=wav_path,
                session_started_at=session_started_at,
                pace_realtime=pace_realtime,
                max_seconds=max_seconds,
                sample_rate=sample_rate,
                detection_records=detection_records,
                speech_ranges=speech_ranges,
                clip_states=clip_states,
                events=events,
            )
            paths.report_json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            paths.report_md.write_text(self._render_report_markdown(report), encoding="utf-8")

            summary = {
                "session_id": session_id,
                "wav_path": str(wav_path),
                "max_seconds": max_seconds,
                "stream_mode": {
                    "chunk_ms": self.config.chunk_ms,
                    "pace_realtime": pace_realtime,
                    "semantic": "streaming",
                },
                "session_started_at_device": session_started_at.isoformat(),
                "config": json.loads(json.dumps(asdict(self.config), default=str)),
                "stats": report["stats"],
                "paths": {
                    "session_dir": str(paths.session_dir),
                    "detection_log": str(paths.detection_log),
                    "period_log": str(paths.period_log),
                    "event_log": str(paths.event_log),
                    "report_json": str(paths.report_json),
                    "report_md": str(paths.report_md),
                },
            }
            paths.summary_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
            return summary
        finally:
            wav.close()

    def _build_report(
        self,
        *,
        wav_path: Path,
        session_started_at: datetime,
        pace_realtime: bool,
        max_seconds: float | None,
        sample_rate: int,
        detection_records: list[DetectionRecord],
        speech_ranges: list[tuple[float, float]],
        clip_states: dict[int, ClipState],
        events: dict[int, dict[str, Any]],
    ) -> dict[str, Any]:
        total_duration_s = 0.0
        with wave.open(str(wav_path), "rb") as wav_handle:
            total_duration_s = wav_handle.getnframes() / float(wav_handle.getframerate())
        if max_seconds is not None:
            total_duration_s = min(total_duration_s, max_seconds)

        laughter_clip_count = sum(1 for state in clip_states.values() if state.period_label == "laughter")
        context_clip_count = sum(
            1 for state in clip_states.values() if state.period_label == "possible_related_speech_context"
        )
        saved_clip_count = sum(1 for state in clip_states.values() if state.saved_path is not None)

        detection_durations = [max(0.0, record.end_offset_s - record.start_offset_s) for record in detection_records]
        avg_detection_duration = (
            sum(detection_durations) / len(detection_durations) if detection_durations else 0.0
        )
        avg_detection_confidence = (
            sum(record.confidence or 0.0 for record in detection_records) / len(detection_records)
            if detection_records
            else 0.0
        )

        event_summaries: list[dict[str, Any]] = []
        for bucket_id in sorted(events.keys()):
            event = events[bucket_id]
            event_summaries.append(
                {
                    "event_id": event["event_id"],
                    "window_start_offset_s": event["start_offset_s"],
                    "window_end_offset_s": event["end_offset_s"],
                    "laughter_period_count": len(event["laughter_period_ids"]),
                    "context_period_count": len(event["context_period_ids"]),
                    "detection_count": len(event["detection_ids"]),
                }
            )

        report = {
            "meta": {
                "wav_path": str(wav_path),
                "session_started_at_device": session_started_at.isoformat(),
                "sample_rate": sample_rate,
                "processed_duration_s": total_duration_s,
                "streaming": {
                    "chunk_ms": self.config.chunk_ms,
                    "pace_realtime": pace_realtime,
                    "semantic": "realtime-stream-simulation",
                },
            },
            "stats": {
                "detection_count": len(detection_records),
                "speech_range_count": len(speech_ranges),
                "clip_count_total": len(clip_states),
                "clip_count_laughter": laughter_clip_count,
                "clip_count_possible_context": context_clip_count,
                "clip_count_saved": saved_clip_count,
                "event_count": len(events),
                "avg_detection_duration_s": round(avg_detection_duration, 3),
                "avg_detection_confidence": round(avg_detection_confidence, 4),
                "compression_detection_to_period": round(
                    len(detection_records) / laughter_clip_count, 3
                )
                if laughter_clip_count
                else None,
                "compression_period_to_event": round(laughter_clip_count / len(events), 3) if events else None,
            },
            "evaluation": {
                "kind": "proxy-structural-evaluation",
                "notes": [
                    "该自动评估不包含人工标注 ground truth，因此衡量的是 detect/cluster 结构统计而非准确率。",
                    "possible_related_speech_context 依赖 speech transcript 区间 + 邻近 laughter clip 规则。",
                    "video/photo 在本地电脑流式 wav 测试中固定为空，仅验证 Detection/Period/Event 三层逻辑。",
                ],
                "highlights": [
                    f"共检测到 {len(detection_records)} 条 detection-layer laughter 记录。",
                    f"聚合得到 {laughter_clip_count} 个 laughter period、{context_clip_count} 个 context period。",
                    f"进一步聚合得到 {len(events)} 个 coarse laughter event。",
                ],
            },
            "top_events": event_summaries[:10],
        }
        return report

    def _render_report_markdown(self, report: dict[str, Any]) -> str:
        stats = report["stats"]
        lines = [
            "# Brief Report",
            "",
            "## Summary",
            "",
            f"- processed_duration_s: {report['meta']['processed_duration_s']:.3f}",
            f"- detection_count: {stats['detection_count']}",
            f"- clip_count_laughter: {stats['clip_count_laughter']}",
            f"- clip_count_possible_context: {stats['clip_count_possible_context']}",
            f"- event_count: {stats['event_count']}",
            f"- avg_detection_duration_s: {stats['avg_detection_duration_s']}",
            f"- avg_detection_confidence: {stats['avg_detection_confidence']}",
            "",
            "## Evaluation Notes",
            "",
        ]
        for note in report["evaluation"]["notes"]:
            lines.append(f"- {note}")

        if report["top_events"]:
            lines.extend(["", "## Top Events", ""])
            for event in report["top_events"]:
                lines.append(
                    "- "
                    + f"{event['event_id']}: window=({event['window_start_offset_s']:.1f}s, "
                    + f"{event['window_end_offset_s']:.1f}s), "
                    + f"laughter_period_count={event['laughter_period_count']}, "
                    + f"context_period_count={event['context_period_count']}, "
                    + f"detection_count={event['detection_count']}"
                )

        return "\n".join(lines) + "\n"
