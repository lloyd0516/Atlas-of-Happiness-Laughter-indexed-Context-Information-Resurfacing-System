from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

import websockets
from websockets.asyncio.client import ClientConnection

from .contracts import LaughterEvent, SpeechmaticsSessionConfig

logger = logging.getLogger(__name__)


class SpeechmaticsRealtimeClient:
    def __init__(self, url: str, api_key: str) -> None:
        self.url = url
        self.api_key = api_key
        self._connection: ClientConnection | None = None

    async def __aenter__(self) -> "SpeechmaticsRealtimeClient":
        logger.info("Opening Speechmatics websocket: %s", self.url)
        self._connection = await websockets.connect(
            self.url,
            extra_headers={"Authorization": f"Bearer {self.api_key}"},
            max_size=None,
        )
        logger.info("Speechmatics websocket connected.")
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        if self._connection is not None:
            await self._connection.close()
            self._connection = None

    @property
    def connection(self) -> ClientConnection:
        if self._connection is None:
            raise RuntimeError("Speechmatics connection has not been opened.")
        return self._connection

    async def start_recognition(self, config: SpeechmaticsSessionConfig) -> dict[str, Any]:
        logger.info("Sending StartRecognition payload.")
        await self.connection.send(json.dumps(config.start_recognition_payload()))
        while True:
            message = await self.connection.recv()
            if isinstance(message, bytes):
                continue
            payload = json.loads(message)
            logger.debug("Speechmatics message before recognition start: %s", payload)
            if payload.get("message") == "RecognitionStarted":
                logger.info("Speechmatics recognition started.")
                return payload
            if payload.get("message") == "Error":
                raise RuntimeError(f"Speechmatics error: {payload}")

    async def send_audio_chunk(self, chunk: bytes) -> None:
        await self.connection.send(chunk)

    async def end_stream(self) -> None:
        logger.info("Sending EndOfStream.")
        await self.connection.send(json.dumps({"message": "EndOfStream"}))

    async def iter_messages(self) -> AsyncIterator[dict[str, Any]]:
        while True:
            raw = await self.connection.recv()
            if isinstance(raw, bytes):
                continue
            payload = json.loads(raw)
            logger.debug("Speechmatics message: %s", payload)
            yield payload
            if payload.get("message") == "EndOfTranscript":
                logger.info("Speechmatics transcript ended.")
                break

    async def run_audio(
        self,
        config: SpeechmaticsSessionConfig,
        chunk_iterator: AsyncIterator[bytes],
        message_handler: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> dict[str, Any]:
        recognition_started = await self.start_recognition(config)

        async def _producer() -> None:
            async for chunk in chunk_iterator:
                await self.send_audio_chunk(chunk)
            await self.end_stream()

        async def _consumer() -> None:
            async for payload in self.iter_messages():
                await message_handler(payload)

        producer = asyncio.create_task(_producer())
        consumer = asyncio.create_task(_consumer())
        done, pending = await asyncio.wait({producer, consumer}, return_when=asyncio.FIRST_EXCEPTION)
        for task in pending:
            task.cancel()
        for task in done:
            exception = task.exception()
            if exception is not None:
                raise exception
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        return recognition_started


def parse_laughter_event(payload: dict[str, Any]) -> LaughterEvent | None:
    message_type = payload.get("message")
    if message_type not in {"AudioEventStarted", "AudioEventEnded"}:
        return None

    event = payload.get("event", {})
    event_type = event.get("type")
    if event_type != "laughter":
        return None

    return LaughterEvent(
        phase="started" if message_type == "AudioEventStarted" else "ended",
        event_type=str(event_type),
        start_time=event.get("start_time"),
        end_time=event.get("end_time"),
        confidence=event.get("confidence"),
        channel=payload.get("channel"),
        raw_message=message_type,
    )
