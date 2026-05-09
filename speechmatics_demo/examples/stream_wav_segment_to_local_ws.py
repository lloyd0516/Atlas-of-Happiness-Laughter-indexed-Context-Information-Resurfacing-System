from __future__ import annotations

import argparse
import asyncio
import json
import wave
from pathlib import Path

import websockets


async def main() -> None:
    parser = argparse.ArgumentParser(
        description="Stream a segment of a WAV file to the local laughter demo websocket in realtime."
    )
    parser.add_argument("--url", default="ws://127.0.0.1:8010/api/v1/laughter/stream")
    parser.add_argument("--file", default="raw/2000_01_01_21_54_53.wav")
    parser.add_argument("--start-sec", type=float, default=1800.0, help="Segment start in seconds.")
    parser.add_argument("--end-sec", type=float, default=3600.0, help="Segment end in seconds.")
    parser.add_argument("--chunk-ms", type=int, default=200)
    parser.add_argument("--language", default="en")
    args = parser.parse_args()

    wav_path = Path(args.file)
    if not wav_path.exists():
        raise FileNotFoundError(f"WAV file not found: {wav_path}")

    with wave.open(str(wav_path), "rb") as wav_file:
        channels = wav_file.getnchannels()
        sample_width = wav_file.getsampwidth()
        sample_rate = wav_file.getframerate()
        total_frames = wav_file.getnframes()
        total_duration = total_frames / sample_rate

        if channels != 1 or sample_width != 2:
            raise ValueError("This example expects a mono 16-bit PCM WAV file.")
        if not 0 <= args.start_sec < args.end_sec <= total_duration:
            raise ValueError(
                f"Invalid segment range: start={args.start_sec}, end={args.end_sec}, file_duration={total_duration}"
            )

        start_frame = int(args.start_sec * sample_rate)
        end_frame = int(args.end_sec * sample_rate)
        frames_per_chunk = int(sample_rate * (args.chunk_ms / 1000.0))

        print(
            f"Streaming {wav_path} from {args.start_sec:.2f}s to {args.end_sec:.2f}s "
            f"({(args.end_sec - args.start_sec) / 60:.2f} min) to {args.url}"
        )

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
                wav_file.setpos(start_frame)
                frames_left = end_frame - start_frame
                while frames_left > 0:
                    frame_count = min(frames_per_chunk, frames_left)
                    chunk = wav_file.readframes(frame_count)
                    if not chunk:
                        break
                    await websocket.send(chunk)
                    frames_left -= frame_count
                    await asyncio.sleep(args.chunk_ms / 1000.0)
                await websocket.send(json.dumps({"message": "end"}))

            async def _receive_messages() -> None:
                async for message in websocket:
                    print(message)

            await asyncio.gather(_send_audio(), _receive_messages())


if __name__ == "__main__":
    asyncio.run(main())
