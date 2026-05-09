from __future__ import annotations

import asyncio
import json
import logging
from collections import deque
from pathlib import Path
from traceback import format_exc
from uuid import uuid4

from fastapi import FastAPI, File, Query, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .audio_sources import AudioSourceError
from .config import Settings
from .contracts import StreamInitRequest
from .service import RealtimeLaughterService
from .speechmatics_client import SpeechmaticsRealtimeClient, parse_laughter_event

app = FastAPI(title="Speechmatics Realtime Laughter Demo", version="0.1.0")
STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
logger = logging.getLogger("speechmatics_demo")
recent_logs: deque[str] = deque(maxlen=200)


def _configure_logging() -> None:
    if logger.handlers:
        return
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def log_event(level: int, message: str, *args) -> None:
    rendered = message % args if args else message
    recent_logs.append(rendered)
    logger.log(level, rendered)


_configure_logging()


class SaveSessionRequest(BaseModel):
    session_id: str
    started_at: str | None = None
    source: str = "browser_microphone"
    language: str = "en"
    events: list[dict]


def get_service() -> RealtimeLaughterService:
    settings = Settings.from_env()
    return RealtimeLaughterService(settings)


@app.get("/healthz")
async def healthcheck() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/debug/logs")
async def get_recent_logs() -> dict[str, list[str]]:
    return {"logs": list(recent_logs)}


@app.get("/")
async def microphone_demo_page() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.post("/api/v1/laughter/from-file")
async def detect_laughter_from_file(
    file: UploadFile = File(...),
    pace_realtime: bool = Query(True),
    chunk_ms: int = Query(200, ge=20, le=2000),
    language: str | None = Query(None),
) -> JSONResponse:
    service = get_service()
    wav_bytes = await file.read()
    try:
        result = await service.process_wav_bytes(
            wav_bytes=wav_bytes,
            source_name=f"upload:{file.filename}",
            chunk_ms=chunk_ms,
            pace_realtime=pace_realtime,
            language=language,
        )
    except (AudioSourceError, ValueError) as exc:
        return JSONResponse(status_code=400, content={"error": str(exc)})
    except RuntimeError as exc:
        return JSONResponse(status_code=502, content={"error": str(exc)})
    return JSONResponse(content=result)


@app.post("/api/v1/laughter/save-session")
async def save_laughter_session(payload: SaveSessionRequest) -> JSONResponse:
    service = get_service()
    result = service.save_browser_session(
        session_id=payload.session_id,
        started_at=payload.started_at,
        source=payload.source,
        language=payload.language,
        events=payload.events,
    )
    return JSONResponse(content=result)


@app.websocket("/api/v1/laughter/stream")
async def laughter_stream(websocket: WebSocket) -> None:
    await websocket.accept()
    session_id = uuid4().hex
    log_event(logging.INFO, "Browser websocket accepted: session_id=%s", session_id)
    try:
        service = get_service()
        init_message = await websocket.receive_text()
        stream_request = StreamInitRequest.from_payload(json.loads(init_message))
        session_config = service.build_session_config_from_stream(stream_request)
        log_event(
            logging.INFO,
            "Session initialized: session_id=%s language=%s sample_rate=%s chunk_ms=%s",
            session_id,
            stream_request.language,
            stream_request.sample_rate,
            stream_request.chunk_ms,
        )

        await websocket.send_json(
            {
                "type": "session.started",
                "data": {
                    "session_id": session_id,
                    "sample_rate": stream_request.sample_rate,
                    "encoding": stream_request.encoding,
                    "language": stream_request.language,
                    "event_types": stream_request.event_types,
                },
            }
        )

        async with SpeechmaticsRealtimeClient(
            url=service.settings.speechmatics_rt_url,
            api_key=service.settings.speechmatics_api_key,
        ) as client:
            recognition_started = await client.start_recognition(session_config)
            log_event(logging.INFO, "Recognition started: session_id=%s", session_id)
            await websocket.send_json({"type": "recognition.started", "data": recognition_started})

            async def _read_client_audio() -> None:
                while True:
                    message = await websocket.receive()
                    if message.get("type") == "websocket.disconnect":
                        await client.end_stream()
                        break
                    if "bytes" in message and message["bytes"] is not None:
                        await client.send_audio_chunk(message["bytes"])
                        continue
                    if "text" in message and message["text"] is not None:
                        payload = json.loads(message["text"])
                        if payload.get("message") == "end":
                            await client.end_stream()
                            break

            async def _forward_server_messages() -> None:
                async for payload in client.iter_messages():
                    laughter_event = parse_laughter_event(payload)
                    if laughter_event is not None:
                        log_event(
                            logging.INFO,
                            "Laughter event: session_id=%s phase=%s start=%s end=%s",
                            session_id,
                            laughter_event.phase,
                            laughter_event.start_time,
                            laughter_event.end_time,
                        )
                        await websocket.send_json(
                            {
                                "type": f"audio_event.{laughter_event.phase}",
                                "data": laughter_event.to_payload(),
                            }
                        )
                        if laughter_event.phase == "started":
                            await websocket.send_json(
                                {
                                    "type": "laughter.detected",
                                    "data": {
                                        "message": "laughter detected",
                                        **laughter_event.to_payload(),
                                    },
                                }
                            )
                        else:
                            await websocket.send_json(
                                {
                                    "type": "laughter.segment",
                                    "data": {
                                        "message": "laughter detected",
                                        **laughter_event.to_payload(),
                                    },
                                }
                            )
                    else:
                        await websocket.send_json({"type": "speechmatics.message", "data": payload})
                await websocket.send_json({"type": "session.completed", "data": {"session_id": session_id}})
                log_event(logging.INFO, "Session completed: session_id=%s", session_id)

            producer = asyncio.create_task(_read_client_audio())
            consumer = asyncio.create_task(_forward_server_messages())
            done, pending = await asyncio.wait({producer, consumer}, return_when=asyncio.FIRST_EXCEPTION)
            for task in pending:
                task.cancel()
            for task in done:
                exception = task.exception()
                if exception is not None:
                    raise exception
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
    except WebSocketDisconnect:
        log_event(logging.INFO, "Browser websocket disconnected: session_id=%s", session_id)
        return
    except Exception as exc:
        tb = format_exc()
        log_event(logging.ERROR, "Session error: session_id=%s error=%s", session_id, tb)
        await websocket.send_json(
            {
                "type": "error",
                "data": {
                    "message": str(exc),
                    "error_type": exc.__class__.__name__,
                    "details": tb,
                },
            }
        )
        await websocket.close()
