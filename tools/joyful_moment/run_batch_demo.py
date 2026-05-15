from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from .config import JoyfulMomentConfig
from .pipeline import JoyfulMomentPipeline


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Batch-run joyful moment streaming demos on multiple WAV files.")
    parser.add_argument(
        "--wav",
        dest="wavs",
        action="append",
        type=Path,
        help="WAV path to run. Can be provided multiple times. Defaults to the 2h and 4h raw files.",
    )
    parser.add_argument("--config", type=Path, default=Path("tools/joyful_moment/config.json"))
    parser.add_argument("--pace-realtime", action="store_true", help="Send audio in wall-clock realtime.")
    parser.add_argument(
        "--max-seconds",
        type=float,
        default=None,
        help="Optional cap for each file. If omitted, each WAV is processed fully.",
    )
    parser.add_argument(
        "--output-report",
        type=Path,
        default=Path("run_logs/joyful_moment/brief_report_batch.json"),
        help="Where to write the aggregated batch report.",
    )
    return parser.parse_args()


async def _amain() -> int:
    args = _parse_args()
    config = JoyfulMomentConfig.from_json_path(args.config) if args.config.exists() else JoyfulMomentConfig()
    pipeline = JoyfulMomentPipeline(config)
    wavs = args.wavs or [
        Path("raw/2000_01_02_09_22_00.wav"),
        Path("raw/2000_01_01_21_54_53.wav"),
    ]

    results: list[dict[str, object]] = []
    for wav in wavs:
        summary = await pipeline.run_wav_path(wav, pace_realtime=bool(args.pace_realtime), max_seconds=args.max_seconds)
        report_path = Path(summary["paths"]["report_json"])
        report = json.loads(report_path.read_text(encoding="utf-8"))
        results.append(
            {
                "wav_path": str(wav),
                "session_dir": summary["paths"]["session_dir"],
                "summary": summary,
                "brief_report": report,
            }
        )
        print(f"{wav} -> {summary['paths']['session_dir']}")

    payload = {"runs": results}
    args.output_report.parent.mkdir(parents=True, exist_ok=True)
    args.output_report.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(args.output_report)
    return 0


def main() -> None:
    raise SystemExit(asyncio.run(_amain()))


if __name__ == "__main__":
    main()
