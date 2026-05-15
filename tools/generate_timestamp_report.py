from __future__ import annotations

import argparse
import csv
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]


def read_manifest_counts(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    with path.open("r", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            key = row.get("label", "unknown")
            counts[key] = counts.get(key, 0) + 1
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate timestamp benchmark report.")
    parser.add_argument("--dataset-name", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    summary_path = ROOT / "output" / "timestamp_benchmarks" / args.dataset_name / "summary.csv"
    summary_df = pd.read_csv(summary_path) if summary_path.exists() else pd.DataFrame()
    counts = read_manifest_counts(args.manifest)
    lines = [
        "# Laughter Detection Timestamp-Level Benchmark Report",
        "",
        "## Interpretation",
        "",
        "- This report uses timestamp-level overlap metrics instead of clip-level binary labels.",
        "- Precision / recall / F1 here are computed from overlap time between predicted laughter segments and annotated laughter regions.",
        "",
        "## Dataset",
        "",
    ]
    for key, value in sorted(counts.items()):
        lines.append(f"- `{key}`: {value}")
    lines.extend(["", "## Results", ""])
    if summary_df.empty:
        lines.append("- No summary found.")
    else:
        for _, row in summary_df.sort_values(["f1", "recall"], ascending=False).iterrows():
            latency = row["average_latency_ms"] if pd.notna(row.get("average_latency_ms")) else row.get("average_filter_latency_ms", 0.0)
            lines.append(
                f"- `{row['method']}` @ `{row['threshold']}`: precision={row['precision']:.3f}, recall={row['recall']:.3f}, f1={row['f1']:.3f}, avg_latency={float(latency):.1f} ms/item"
            )
    report_path = ROOT / "reports" / f"{args.dataset_name}_timestamp_benchmark_report.md"
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
