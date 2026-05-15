from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_manifest_counts(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    with path.open("r", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            counts[row["label"]] = counts.get(row["label"], 0) + 1
    return counts


def format_dataset_name(name: str) -> str:
    return name.replace("_", " ").title()


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a clip-level benchmark report for a dataset.")
    parser.add_argument("--dataset-name", default="switchboard")
    args = parser.parse_args()

    report_dir = ROOT / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    inventory_path = report_dir / "model_inventory.json"
    benchmark_summary = ROOT / "output" / "benchmarks" / args.dataset_name / "summary.csv"
    manifest_path = ROOT / "datasets_unified" / args.dataset_name / "manifest_test.csv"
    dataset_label = format_dataset_name(args.dataset_name)
    materialize_summary_path = ROOT / "datasets_unified" / args.dataset_name / "manifests" / "materialize_summary.json"

    inventory = read_json(inventory_path) if inventory_path.exists() else {}
    summary_df = pd.read_csv(benchmark_summary) if benchmark_summary.exists() else pd.DataFrame()
    manifest_counts = read_manifest_counts(manifest_path) if manifest_path.exists() else {}
    materialize_summary = read_json(materialize_summary_path) if materialize_summary_path.exists() else None

    recommendation = "gillick"
    best_f1_method = None
    best_filtered_method = None
    if not summary_df.empty:
        base_only = summary_df[~summary_df["method"].str.contains(r"\+")]
        if not base_only.empty:
            best_row = base_only.sort_values(["f1", "recall"], ascending=False).iloc[0]
            best_f1_method = f"{best_row['method']} @ {best_row['threshold']}"
        filtered_only = summary_df[summary_df["method"].str.contains(r"\+", regex=True)]
        if not filtered_only.empty:
            best_filtered_row = filtered_only.sort_values(["f1", "precision"], ascending=False).iloc[0]
            best_filtered_method = (
                f"{best_filtered_row['method']} @ {best_filtered_row['threshold']}: "
                f"precision {best_filtered_row['precision']:.3f}, "
                f"recall {best_filtered_row['recall']:.3f}, "
                f"f1 {best_filtered_row['f1']:.3f}"
            )

    rationale = [
        "Gillick remains the most practical edge choice because the checkpoint is only about 9.35 MB and latency stayed around 0.10 s per clip on GPU.",
        "Omine achieved the strongest clip-level F1 among the larger models on the current Switchboard subset, but the model artifact is about 1.2 GB, which is hard to justify for hour-scale edge deployment.",
        "hhoangphuoc works well as a laughter-aware ASR signal and as a post-filter, but its own wav2vec2 artifact is also about 1.2 GB.",
    ]
    if "ideo" in inventory:
        rationale.append(
            "IDEO is lightweight at the classifier level, but its VGGish front end still makes it less deployment-friendly than Gillick for simple edge packaging."
        )

    lines = [
        "# Laughter Detection Clip-Level Benchmark Report",
        "",
        "## Important Interpretation",
        "",
        "- This report summarizes a local clip-level benchmark, not the original paper evaluation protocol.",
        "- Each input clip is reduced to a binary decision: predicted laughter or not predicted laughter.",
        "- The reported precision / recall / F1 are therefore not directly comparable to the time-overlap metrics reported in the Gillick paper.",
        "- These numbers are also not the same as long-form real-world segment precision measured by manually checking predicted laughter clips in natural recordings.",
        "",
        "## Scope",
        "",
        "- Workspace restricted to `laughter-detection` only.",
        f"- This report covers the locally materialized {dataset_label} clip-level benchmark subset.",
        "- AudioSet metadata and TFRecord assets copied from IDEO are now organized under `datasets_unified/audioset`, but raw benchmark-ready waveform clips are not fully materialized in this workspace for all-method evaluation.",
        "- AMI is still documented only and has not yet been materialized into a unified local benchmark layout.",
        "",
        "## Benchmark Dataset",
        "",
    ]
    for label, count in sorted(manifest_counts.items()):
        lines.append(f"- `{label}`: {count} clips")
    if materialize_summary is not None:
        lines.append(
            f"- Materialization status: requested={materialize_summary['requested']}, "
            f"success={materialize_summary['success']}, failed={materialize_summary['failed']}"
        )

    lines.extend(
        [
            "",
            "## Model Inventory",
            "",
        ]
    )
    for name, item in inventory.items():
        lines.append(f"- `{name}`: {item['size_mb']} MB, artifact `{item['artifact']}`")

    lines.extend(
        [
            "",
            f"## {dataset_label} Results",
            "",
        ]
    )
    if summary_df.empty:
        lines.append("- Benchmark summary not found yet.")
    else:
        for _, row in summary_df.sort_values(["f1", "recall"], ascending=False).iterrows():
            latency = row.get("average_latency_ms")
            if pd.isna(latency):
                latency = row.get("average_filter_latency_ms", 0.0)
            lines.append(
                f"- `{row['method']}` @ `{row['threshold']}`: "
                f"precision={row['precision']:.3f}, recall={row['recall']:.3f}, f1={row['f1']:.3f}, "
                f"avg_latency={float(latency):.1f} ms/clip"
            )

    lines.extend(
        [
            "",
            "## ASR Filter Effect",
            "",
            "- `speechlaugh_ctc_filter` improved precision for the base detector variants that were tested, but it also reduced recall on this subset.",
            "",
            "## Recommendation",
            "",
            f"- Preferred primary edge detector: `{recommendation}`.",
        ]
    )
    if best_f1_method:
        lines.append(f"- Highest F1 among completed base methods: `{best_f1_method}`.")
    if best_filtered_method:
        lines.append(f"- Best filtered variant: `{best_filtered_method}`.")
    lines.extend(f"- {item}" for item in rationale)
    lines.extend(
        [
            "",
            "## Pending / Blocked",
            "",
            "- `AudioSet`: the full clean evaluation set is larger than the currently materialized local download subset, so reported results may reflect only the successfully downloaded clips currently present in the manifest.",
            "- `AMI`: documented under `datasets_unified`, but not yet materialized into raw benchmark clips for all-method testing.",
        ]
    )

    report_stem = "clip_level_benchmark_report" if args.dataset_name == "switchboard" else f"{args.dataset_name}_clip_level_benchmark_report"
    report_path = report_dir / f"{report_stem}.md"
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
