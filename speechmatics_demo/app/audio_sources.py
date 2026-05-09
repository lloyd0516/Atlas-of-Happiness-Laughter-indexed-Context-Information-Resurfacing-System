from __future__ import annotations

import asyncio
import io
import wave
from dataclasses import dataclass
from typing import AsyncIterator

from .contracts import AudioFormat


class AudioSourceError(ValueError):
    """Raised when the supplied audio source is not supported by the demo."""


@dataclass(slots=True)
class WavPcm16AudioSource:
    source_name: str
    wav_bytes: bytes
    chunk_ms: int = 200
    pace_realtime: bool = True

    def __post_init__(self) -> None:
        self._validate_chunk_ms()
        with wave.open(io.BytesIO(self.wav_bytes), "rb") as wav_file:
            channels = wav_file.getnchannels()
            sample_width = wav_file.getsampwidth()
            sample_rate = wav_file.getframerate()
            if channels != 1:
                raise AudioSourceError("Only mono WAV files are supported in this demo.")
            if sample_width != 2:
                raise AudioSourceError("Only 16-bit PCM WAV files are supported in this demo.")
            self.sample_rate = sample_rate
            self.chunk_bytes = int(sample_rate * (self.chunk_ms / 1000.0) * sample_width)
            self.audio_format = AudioFormat(type="raw", encoding="pcm_s16le", sample_rate=sample_rate)

    def _validate_chunk_ms(self) -> None:
        if self.chunk_ms <= 0:
            raise AudioSourceError("chunk_ms must be greater than zero.")

    async def iter_chunks(self) -> AsyncIterator[bytes]:
        with wave.open(io.BytesIO(self.wav_bytes), "rb") as wav_file:
            while True:
                chunk = wav_file.readframes(self.chunk_bytes // 2)
                if not chunk:
                    break
                yield chunk
                if self.pace_realtime:
                    await asyncio.sleep(self.chunk_ms / 1000.0)
