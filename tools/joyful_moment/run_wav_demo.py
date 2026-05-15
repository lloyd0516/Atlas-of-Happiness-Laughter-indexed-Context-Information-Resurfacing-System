from __future__ import annotations

import argparse
import asyncio
from pathlib import Path

from .config import JoyfulMomentConfig
from .pipeline import JoyfulMomentPipeline


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Joyful moment pipeline: stream a long WAV into Speechmatics and cluster.")
    p.add_argument("--wav", type=Path, required=True, help="Path to mono 16-bit PCM WAV.")
    p.add_argument("--config", type=Path, default=Path("tools/joyful_moment/config.json"), help="Config JSON path.")
    p.add_argument("--max-seconds", type=float, default=None, help="Only stream the first N seconds of the WAV.")
    p.add_argument(
        "--pace-realtime",
        action="store_true",
        help="Sleep between chunks to match realtime (WARNING: 4h WAV will take ~4h).",
    )
    return p.parse_args()


async def _amain() -> int:
    args = _parse_args()
    config = JoyfulMomentConfig.from_json_path(args.config) if args.config.exists() else JoyfulMomentConfig()
    pipeline = JoyfulMomentPipeline(config)
    summary = await pipeline.run_wav_path(
        args.wav,
        pace_realtime=bool(args.pace_realtime),
        max_seconds=args.max_seconds,
    )
    print(summary["paths"]["session_dir"])
    return 0


def main() -> None:
    raise SystemExit(asyncio.run(_amain()))


if __name__ == "__main__":
    main()
