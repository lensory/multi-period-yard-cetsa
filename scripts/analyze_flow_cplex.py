from __future__ import annotations

import argparse
import gzip
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable

import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns


LOG_TS = "%Y-%m-%d %H:%M:%S.%f"
RUN_DIR_RE = re.compile(
    r"^(?P<scale>\d{2}-\d{2}-\d{2})_(?P<yard>\d{2}-\d{2})_(?P<seed>\d{2})_flowBasedCplex_"
)
ROLL_RE = re.compile(r"^experiment\.(?P<date>\d{4}-\d{2}-\d{2})\.(?P<idx>\d+)\.log\.gz$")
MEM_RE = re.compile(
    r"Memory: RSS=(?P<rss>\d+)/(?P<rss_limit>\d+) MB .*?"
    r"heap=(?P<heap_used>\d+)/(?P<heap_committed>\d+)/(?P<heap_max>\d+) MB .*?"
    r"nonHeap=(?P<nonheap_used>\d+)/(?P<nonheap_committed>\d+) MB .*?"
    r"direct=(?P<direct>\d+) MB, mapped=(?P<mapped>\d+) MB, nativeOtherApprox=(?P<native_other>\d+) MB"
)
CONFIG_MEM_RE = re.compile(
    r"memory: rss_limit=(?P<rss_limit>\d+) MB, work_mem=(?P<work_mem>\d+) MB, "
    r"tree_mem=(?P<tree_mem>\d+) MB, heap_max=(?P<heap_max>\d+) MB"
)
FINAL_RE = re.compile(
    r"Vessels=\((?P<vessels>[^)]+)\), Yard=\((?P<yard>[^)]+)\), Seed=(?P<seed>\d+): "
    r"(?:(?P<status>Failed)|obj=(?P<obj>[-+0-9.eE]+).*?runningTime=(?P<runtime>[-+0-9.eE]+)s)"
)
OBJ_RE = re.compile(r"Solved by flow-based CPLEX: Objective=(?P<obj>[-+0-9.eE]+)")
BOUND_RE = re.compile(r"Best bound = (?P<bound>[-+0-9.eE]+)")
GAP_RE = re.compile(r"Relative gap = (?P<gap>[-+0-9.eE]+)")
STATUS_RE = re.compile(r"CPLEX status = (?P<status>.+)$")
CPLEX_ELAPSED_RE = re.compile(r"Elapsed time = (?P<elapsed>[-+0-9.eE]+) sec\.")
ROOT_RELAX_RE = re.compile(r"Root relaxation solution time = (?P<elapsed>[-+0-9.eE]+) sec\.")


@dataclass
class RunData:
    run_dir: Path
    scale: str
    yard: str
    seed: int
    skipped: bool = False
    status: str | None = None
    cplex_status: str | None = None
    final_bound: float | None = None
    final_obj: float | None = None
    final_gap: float | None = None
    runtime_sec: float | None = None
    rss_limit_mb: float | None = None
    work_mem_mb: float | None = None
    tree_mem_mb: float | None = None
    heap_max_mb: float | None = None
    max_rss_mb: float | None = None
    max_native_other_mb: float | None = None
    max_heap_used_mb: float | None = None
    first_ts: datetime | None = None
    last_ts: datetime | None = None
    max_cplex_elapsed_sec: float | None = None
    root_relax_sec: float | None = None
    has_rolled_logs: bool = False
    log_files: int = 0
    notes: list[str] = field(default_factory=list)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-dir",
        type=Path,
        default=Path("output/flow_cplex_scale_8h"),
    )
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument(
        "--skip-20-05-05-tail",
        action="store_true",
        help="Skip 20-05-05 seeds 4 and 5 for older incomplete result sets.",
    )
    return parser.parse_args()


def run_identity(run_dir: Path) -> tuple[str, str, int] | None:
    match = RUN_DIR_RE.match(run_dir.name)
    if not match:
        return None
    return match.group("scale"), match.group("yard"), int(match.group("seed"))


def ordered_log_files(run_dir: Path) -> list[Path]:
    rolled: list[tuple[str, int, Path]] = []
    for path in run_dir.glob("experiment.*.log.gz"):
        match = ROLL_RE.match(path.name)
        if match:
            rolled.append((match.group("date"), int(match.group("idx")), path))
    files = [item[2] for item in sorted(rolled)]
    current = run_dir / "experiment.log"
    if current.exists():
        files.append(current)
    return files


def iter_log_lines(files: Iterable[Path]) -> Iterable[tuple[str, str]]:
    for path in files:
        opener = gzip.open if path.suffix == ".gz" else open
        with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                yield path.name, line.rstrip("\n")


def parse_ts(line: str) -> datetime | None:
    if len(line) < 23:
        return None
    try:
        return datetime.strptime(line[:23], LOG_TS)
    except ValueError:
        return None


def message_part(line: str) -> str:
    marker = " - "
    if marker in line:
        return line.split(marker, 1)[1].strip()
    return line.strip()


def to_float(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def elapsed_since_start(ts: datetime | None, start: datetime | None) -> float | None:
    if ts is None or start is None:
        return None
    return max(0.0, (ts - start).total_seconds())


def parse_mip_progress(
    msg: str, last_ub: float | None, last_lb: float | None
) -> tuple[float | None, float | None, float | None] | None:
    if not re.match(r"^[*+\s]*\d+\+?\s+\d+\s+", msg):
        return None
    if "Cuts:" in msg:
        if last_ub is None and last_lb is None:
            return None
        return last_ub, last_lb, None

    row = msg.replace("|", " ")
    parts = row.split()
    if len(parts) < 3:
        return None
    if parts[0] in {"*", "+"}:
        parts = parts[1:]
    if len(parts) < 3:
        return None

    rest = parts[2:]
    has_gap = rest[-1].endswith("%") if rest else False
    nums = [to_float(token.rstrip("%")) for token in rest if to_float(token.rstrip("%")) is not None]
    if not nums:
        return None

    gap = nums[-1] if has_gap else None
    core = nums[:-1] if has_gap else nums

    ub: float | None = None
    lb: float | None = None
    if has_gap:
        # With a gap, CPLEX has an incumbent and a bound. Some incumbent-update
        # rows omit Objective/IInf/ItCnt, so read UB/LB from the right.
        if len(core) >= 3:
            lb = core[-2]
            ub = core[-3]
        elif len(core) >= 2:
            lb = core[-1]
            ub = core[-2]
    else:
        # No gap usually means no incumbent yet. For root rows like
        # "0 0 24.7074 1768 24.7074 9037", the Best Bound is the penultimate
        # numeric field and UB should remain empty.
        if len(core) >= 4:
            lb = core[-2]
            ub = last_ub
        elif len(core) >= 2:
            lb = core[-1]
            ub = last_ub

    if ub is None and lb is None:
        return None
    return ub, lb, gap


def parse_run(run_dir: Path, skip: bool) -> tuple[RunData, list[dict], list[dict]]:
    identity = run_identity(run_dir)
    if identity is None:
        raise ValueError(f"Cannot parse run directory name: {run_dir}")
    scale, yard, seed = identity
    data = RunData(run_dir=run_dir, scale=scale, yard=yard, seed=seed, skipped=skip)
    if skip:
        data.status = "Skipped"
        data.notes.append("skipped by rule")
        return data, [], []

    files = ordered_log_files(run_dir)
    data.log_files = len(files)
    data.has_rolled_logs = any(path.suffix == ".gz" for path in files)
    bounds: list[dict] = []
    memory: list[dict] = []
    last_ub: float | None = None
    last_lb: float | None = None

    for _, line in iter_log_lines(files):
        ts = parse_ts(line)
        if ts is not None:
            data.first_ts = data.first_ts or ts
            data.last_ts = ts
        msg = message_part(line)

        if match := CONFIG_MEM_RE.search(msg):
            data.rss_limit_mb = to_float(match.group("rss_limit"))
            data.work_mem_mb = to_float(match.group("work_mem"))
            data.tree_mem_mb = to_float(match.group("tree_mem"))
            data.heap_max_mb = to_float(match.group("heap_max"))
            continue

        if match := MEM_RE.search(msg):
            row = {
                "scale": scale,
                "yard": yard,
                "seed": seed,
                "run_dir": run_dir.name,
                "timestamp": ts,
                "elapsed_sec": elapsed_since_start(ts, data.first_ts),
            }
            for key, value in match.groupdict().items():
                row[f"{key}_mb"] = int(value)
            memory.append(row)
            rss = row["rss_mb"]
            data.max_rss_mb = max(data.max_rss_mb or rss, rss)
            data.rss_limit_mb = data.rss_limit_mb or row["rss_limit_mb"]
            data.max_native_other_mb = max(data.max_native_other_mb or row["native_other_mb"], row["native_other_mb"])
            data.max_heap_used_mb = max(data.max_heap_used_mb or row["heap_used_mb"], row["heap_used_mb"])
            continue

        if match := STATUS_RE.search(msg):
            data.cplex_status = match.group("status").strip()
            continue
        if match := BOUND_RE.search(msg):
            data.final_bound = to_float(match.group("bound"))
            continue
        if match := GAP_RE.search(msg):
            data.final_gap = to_float(match.group("gap"))
            continue
        if match := OBJ_RE.search(msg):
            data.final_obj = to_float(match.group("obj"))
            continue
        if match := FINAL_RE.search(msg):
            data.runtime_sec = to_float(match.group("runtime"))
            data.status = "Failed" if match.group("status") else "Solved"
            if match.group("obj"):
                data.final_obj = to_float(match.group("obj"))
            continue
        if match := ROOT_RELAX_RE.search(msg):
            data.root_relax_sec = to_float(match.group("elapsed"))
            continue
        if match := CPLEX_ELAPSED_RE.search(msg):
            elapsed = to_float(match.group("elapsed"))
            if elapsed is not None:
                data.max_cplex_elapsed_sec = max(data.max_cplex_elapsed_sec or elapsed, elapsed)

        progress = parse_mip_progress(msg, last_ub, last_lb)
        if progress is not None:
            ub, lb, gap = progress
            if ub is not None:
                last_ub = ub
            if lb is not None:
                last_lb = lb
            bounds.append(
                {
                    "scale": scale,
                    "yard": yard,
                    "seed": seed,
                    "run_dir": run_dir.name,
                    "timestamp": ts,
                    "elapsed_sec": elapsed_since_start(ts, data.first_ts),
                    "ub": ub,
                    "lb": lb,
                    "gap_percent": gap,
                }
            )

    if data.status is None:
        data.status = "Unknown"
    if data.runtime_sec is None and data.first_ts and data.last_ts:
        data.runtime_sec = (data.last_ts - data.first_ts).total_seconds()
        data.notes.append("runtime inferred from log timestamps")
    if not bounds:
        data.notes.append("no MIP bound series")
    if not memory:
        data.notes.append("no memory series")
    if data.final_gap is None and data.final_obj not in (None, 0) and data.final_bound is not None:
        data.final_gap = abs(data.final_obj - data.final_bound) / (abs(data.final_obj) + 1e-12)
        data.notes.append("gap computed from obj and bound")
    return data, bounds, memory


def run_to_dict(data: RunData) -> dict:
    rss_ratio = None
    if data.max_rss_mb is not None and data.rss_limit_mb:
        rss_ratio = data.max_rss_mb / data.rss_limit_mb
    return {
        "scale": data.scale,
        "yard": data.yard,
        "seed": data.seed,
        "run_dir": data.run_dir.name,
        "skipped": data.skipped,
        "status": data.status,
        "cplex_status": data.cplex_status,
        "cplex_lowerbound": data.final_bound,
        "cplex_obj": data.final_obj,
        "cplex_gap": data.final_gap,
        "runtime_sec": data.runtime_sec,
        "runtime_hour": data.runtime_sec / 3600 if data.runtime_sec is not None else None,
        "max_rss_mb": data.max_rss_mb,
        "rss_limit_mb": data.rss_limit_mb,
        "rss_peak_ratio": rss_ratio,
        "work_mem_mb": data.work_mem_mb,
        "tree_mem_mb": data.tree_mem_mb,
        "heap_max_mb": data.heap_max_mb,
        "max_heap_used_mb": data.max_heap_used_mb,
        "max_native_other_mb": data.max_native_other_mb,
        "root_relax_sec": data.root_relax_sec,
        "max_cplex_elapsed_sec": data.max_cplex_elapsed_sec,
        "has_rolled_logs": data.has_rolled_logs,
        "log_files": data.log_files,
        "notes": "; ".join(data.notes),
    }


def write_plots(summary: pd.DataFrame, bounds: pd.DataFrame, memory: pd.DataFrame, out_dir: Path) -> None:
    sns.set_theme(style="whitegrid", context="talk")
    scales = sorted(summary["scale"].unique())
    for scale in scales:
        scale_seeds = sorted(summary.loc[summary["scale"] == scale, "seed"].unique())
        palette = sns.color_palette("tab10", n_colors=max(len(scale_seeds), 1))
        seed_colors = dict(zip(scale_seeds, palette))

        scale_bounds = bounds[bounds["scale"] == scale].copy() if not bounds.empty else pd.DataFrame()
        fig, ax = plt.subplots(figsize=(12, 7))
        if not scale_bounds.empty:
            for seed, group in scale_bounds.groupby("seed"):
                group = group.sort_values("elapsed_sec")
                hours = group["elapsed_sec"] / 3600.0
                color = seed_colors.get(seed)
                if group["ub"].notna().any():
                    ax.plot(hours, group["ub"], label=f"seed {seed} UB", color=color, linewidth=1.8)
                if group["lb"].notna().any():
                    ax.plot(hours, group["lb"], label=f"seed {seed} LB", color=color, linestyle="--", linewidth=1.8)
            ax.legend(ncol=2, fontsize=9)
        else:
            ax.text(0.5, 0.5, "No MIP bound series", transform=ax.transAxes, ha="center", va="center")
        ax.set_title(f"{scale} UB/LB over time")
        ax.set_xlabel("Elapsed time (hour)")
        ax.set_ylabel("Objective bound")
        fig.tight_layout()
        fig.savefig(out_dir / f"{scale}_bounds.png", dpi=180)
        plt.close(fig)

        scale_mem = memory[memory["scale"] == scale].copy() if not memory.empty else pd.DataFrame()
        fig, ax = plt.subplots(figsize=(12, 7))
        if not scale_mem.empty:
            for seed, group in scale_mem.groupby("seed"):
                group = group.sort_values("elapsed_sec")
                hours = group["elapsed_sec"] / 3600.0
                color = seed_colors.get(seed)
                ax.plot(hours, group["rss_mb"], label=f"seed {seed} RSS", color=color, linewidth=1.7)
                if group["heap_used_mb"].notna().any():
                    ax.plot(
                        hours,
                        group["heap_used_mb"],
                        label=f"seed {seed} heap",
                        color=color,
                        linestyle=":",
                        linewidth=1.2,
                    )
                if group["native_other_mb"].notna().any():
                    ax.plot(
                        hours,
                        group["native_other_mb"],
                        label=f"seed {seed} native",
                        color=color,
                        linestyle="--",
                        linewidth=1.2,
                    )
            limit = scale_mem["rss_limit_mb"].dropna()
            if not limit.empty:
                ax.axhline(limit.max(), color="black", linestyle="-.", linewidth=1.3, label=f"RSS limit {limit.max():.0f} MB")
            ax.legend(ncol=2, fontsize=8)
        else:
            ax.text(0.5, 0.5, "No memory series", transform=ax.transAxes, ha="center", va="center")
        ax.set_title(f"{scale} memory over time")
        ax.set_xlabel("Elapsed time (hour)")
        ax.set_ylabel("Memory (MB)")
        fig.tight_layout()
        fig.savefig(out_dir / f"{scale}_memory.png", dpi=180)
        plt.close(fig)


def write_notes(summary: pd.DataFrame, out_dir: Path) -> None:
    lines = ["# Flow-based CPLEX Summary Notes", ""]
    lines.append(f"Runs parsed: {len(summary)}")
    lines.append(f"Skipped runs: {int(summary['skipped'].sum())}")
    lines.append("")

    status_counts = summary["status"].fillna("Unknown").value_counts().to_dict()
    lines.append("## Status Counts")
    for status, count in status_counts.items():
        lines.append(f"- {status}: {count}")
    lines.append("")

    no_bounds = summary[summary["notes"].fillna("").str.contains("no MIP bound series", regex=False)]
    if not no_bounds.empty:
        lines.append("## Runs Without MIP Bound Series")
        for _, row in no_bounds.iterrows():
            lines.append(f"- {row['scale']} seed {int(row['seed'])}: {row['status']} ({row['run_dir']})")
        lines.append("")

    high_mem = summary[(summary["rss_peak_ratio"].notna()) & (summary["rss_peak_ratio"] >= 0.9)]
    if not high_mem.empty:
        lines.append("## RSS Peaks >= 90% Of Limit")
        for _, row in high_mem.sort_values("rss_peak_ratio", ascending=False).iterrows():
            lines.append(
                f"- {row['scale']} seed {int(row['seed'])}: "
                f"{row['max_rss_mb']:.0f}/{row['rss_limit_mb']:.0f} MB ({row['rss_peak_ratio']:.1%})"
            )
        lines.append("")

    root = summary[summary["root_relax_sec"].notna()]
    if not root.empty:
        lines.append("## Root Relaxation Observations")
        for _, row in root.sort_values("root_relax_sec", ascending=False).iterrows():
            lines.append(f"- {row['scale']} seed {int(row['seed'])}: root relaxation {row['root_relax_sec']:.1f}s")
        lines.append("")

    unresolved = summary[summary["status"].isin(["Failed", "Unknown"])]
    if not unresolved.empty:
        lines.append("## Failed Or Unknown Runs")
        for _, row in unresolved.iterrows():
            lines.append(f"- {row['scale']} seed {int(row['seed'])}: {row['status']}; notes={row['notes']}")
        lines.append("")

    (out_dir / "notes.md").write_text("\n".join(lines), encoding="utf-8")


def make_scale_summary(summary: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict] = []
    for scale, group in summary.groupby("scale", sort=True):
        solved = group[group["status"] == "Solved"]
        parsed = group[group["status"] != "Skipped"]
        rows.append(
            {
                "scale": scale,
                "runs": len(group),
                "solved": int((group["status"] == "Solved").sum()),
                "failed": int((group["status"] == "Failed").sum()),
                "skipped": int((group["status"] == "Skipped").sum()),
                "avg_obj_solved": solved["cplex_obj"].mean(),
                "avg_gap_solved": solved["cplex_gap"].mean(),
                "max_gap_solved": solved["cplex_gap"].max(),
                "avg_runtime_hour_parsed": parsed["runtime_hour"].mean(),
                "max_runtime_hour_parsed": parsed["runtime_hour"].max(),
                "max_rss_mb": parsed["max_rss_mb"].max(),
                "max_rss_peak_ratio": parsed["rss_peak_ratio"].max(),
                "max_root_relax_sec": parsed["root_relax_sec"].max(),
                "runs_without_bound_series": int(
                    parsed["notes"].fillna("").str.contains("no MIP bound series", regex=False).sum()
                ),
            }
        )
    return pd.DataFrame(rows)


def main() -> None:
    args = parse_args()
    base_dir = args.base_dir
    out_dir = args.out_dir or (base_dir / "summary")
    out_dir.mkdir(parents=True, exist_ok=True)

    run_dirs = sorted(path for path in base_dir.iterdir() if path.is_dir() and run_identity(path))
    summaries: list[dict] = []
    all_bounds: list[dict] = []
    all_memory: list[dict] = []

    for run_dir in run_dirs:
        identity = run_identity(run_dir)
        assert identity is not None
        scale, _, seed = identity
        skip = args.skip_20_05_05_tail and scale == "20-05-05" and seed in {4, 5}
        data, bounds, memory = parse_run(run_dir, skip=skip)
        summaries.append(run_to_dict(data))
        all_bounds.extend(bounds)
        all_memory.extend(memory)

    summary_df = pd.DataFrame(summaries).sort_values(["scale", "seed"])
    bounds_df = pd.DataFrame(all_bounds)
    memory_df = pd.DataFrame(all_memory)
    scale_summary_df = make_scale_summary(summary_df)

    summary_df.to_csv(out_dir / "summary.csv", index=False)
    scale_summary_df.to_csv(out_dir / "scale_summary.csv", index=False)
    bounds_df.to_csv(out_dir / "bound_series.csv", index=False)
    memory_df.to_csv(out_dir / "memory_series.csv", index=False)
    with pd.ExcelWriter(out_dir / "summary.xlsx") as writer:
        summary_df.to_excel(writer, sheet_name="summary", index=False)
        scale_summary_df.to_excel(writer, sheet_name="scale_summary", index=False)
        if not bounds_df.empty:
            bounds_df.to_excel(writer, sheet_name="bound_series", index=False)
        if not memory_df.empty:
            memory_df.to_excel(writer, sheet_name="memory_series", index=False)

    write_plots(summary_df, bounds_df, memory_df, out_dir)
    write_notes(summary_df, out_dir)

    print(f"wrote {len(summary_df)} runs to {out_dir}")
    print(f"bound rows: {len(bounds_df)}, memory rows: {len(memory_df)}")


if __name__ == "__main__":
    main()
