from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare Omine and Speechmatics benchmark rows from summary.csv.")
    parser.add_argument("--dataset-name", default="switchboard")
    parser.add_argument("--summary", type=Path, default=None)
    args = parser.parse_args()

    summary_path = args.summary or (ROOT / "output" / "benchmarks" / args.dataset_name / "summary.csv")
    if not summary_path.exists():
        raise FileNotFoundError(f"Missing summary file: {summary_path}")

    df = pd.read_csv(summary_path)
    df = df[df["method"].isin(["omine", "speechmatics"])].copy()
    if df.empty:
        raise RuntimeError("No omine/speechmatics rows found in summary.csv")

    columns = [
        "method",
        "threshold",
        "accuracy",
        "precision",
        "recall",
        "f1",
        "average_latency_ms",
        "median_latency_ms",
        "num_items",
        "num_positive_ref",
        "num_positive_pred",
    ]
    for col in columns:
        if col not in df.columns:
            df[col] = None

    ordered = df[columns].sort_values(["method", "f1", "recall"], ascending=[True, False, False])
    print(ordered.to_string(index=False))


if __name__ == "__main__":
    main()
