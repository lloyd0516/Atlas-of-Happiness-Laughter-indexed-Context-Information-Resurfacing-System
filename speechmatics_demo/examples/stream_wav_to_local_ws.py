from __future__ import annotations

import argparse
import asyncio
import io
import json
import wave

import websockets


async def main() -> None:
    parser = argparse.ArgumentParser(description="Stream a WAV file to the local laughter demo websocket.")
    parser.add_argument("--url", default="ws://127.0.0.1:8010/api/v1/laughter/stream")
    parser.add_argument("--file", required=True)
    parser.add_argument("--chunk-ms", type=int, default=200)
    parser.add_argument("--language", default="en")
    args = parser.parse_args()

    with open(args.file, "rb") as fh:
        wav_bytes = fh.read()

    with wave.open(io.BytesIO(wav_bytes), "rb") as wav_file:
        if wav_file.getnchannels() != 1 or wav_file.getsampwidth() != 2:
            raise ValueError("This example expects a mono 16-bit PCM WAV file.")

        sample_rate = wav_file.getframerate()
        frames_per_chunk = int(sample_rate * (args.chunk_ms / 1000.0))

        async with websockets.connect(args.url, max_size=None) as websocket:
            await websocket.send(
                json.dumps(
                    {
                        "sample_rate": sample_rate,
                        "encoding": "pcm_s16le",
                        "language": args.language,
                        "chunk_ms": args.chunk_ms,
                        "channels": 1,
                        "event_types": ["laughter"],
                    }
                )
            )

            async def _send_audio() -> None:
                while True:
                    chunk = wav_file.readframes(frames_per_chunk)
                    if not chunk:
                        break
                    await websocket.send(chunk)
                    await asyncio.sleep(args.chunk_ms / 1000.0)
                await websocket.send(json.dumps({"message": "end"}))

            async def _receive_messages() -> None:
                async for message in websocket:
                    print(message)

            await asyncio.gather(_send_audio(), _receive_messages())


if __name__ == "__main__":
    asyncio.run(main())
