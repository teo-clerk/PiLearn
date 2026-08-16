#!/usr/bin/env python3
"""Compare a baseline run against the committed golden snapshots.

Two modes:

    promote   copy a run's per-fixture metrics into golden/ (deliberate, reviewed act)
    check     diff a run against golden/ and exit non-zero on regression

Only DETERMINISTIC fields are compared. Wall time, exit codes, log text and temp paths
live in the run report for humans and are excluded here — otherwise the check would
fail on a slow machine, which teaches everyone to ignore it.

Runs on the host (stdlib only, no music21 needed):

    python3 tools/omr-baseline/harness/compare.py check --run 20260816T101500Z
    python3 tools/omr-baseline/harness/compare.py promote --run 20260816T101500Z
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

BASELINE_DIR = Path(__file__).resolve().parent.parent
REPORTS_DIR = BASELINE_DIR / "reports"
GOLDEN_DIR = BASELINE_DIR / "golden"

# Fields whose change means a real recognition regression.
# Dotted paths into report["metrics"].
COMPARED_FIELDS: tuple[str, ...] = (
    "archive.has_musicxml",
    "archive.has_midi",
    "archive.has_metadata",
    "musicxml.part_count",
    "musicxml.staff_count",
    "musicxml.measure_count",
    "musicxml.note_count",
    "musicxml.rest_count",
    "musicxml.chord_count",
    "musicxml.tied_note_count",
    "musicxml.distinct_pitch_count",
    "musicxml.pitch_min_midi",
    "musicxml.pitch_max_midi",
    "musicxml.key_signatures",
    "musicxml.time_signatures",
    "musicxml.has_fingering",
    "musicxml.has_harmony",
    "musicxml.repeat_count",
    "musicxml.volta_count",
    "musicxml.measure_note_counts",
    "musicxml.content_hash",
    "midi.track_count",
    "midi.note_count",
    "midi.ppq",
    "midi.distinct_pitch_count",
    "midi.pitch_class_histogram",
    "midi.content_hash",
)

# Tolerances for fields where tiny drift is acceptable and not worth a red build.
TOLERANCE: dict[str, float] = {
    "midi.duration_sec": 0.05,
}


def dig(payload: dict, dotted: str) -> Any:
    node: Any = payload
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def summarise(value: Any, limit: int = 90) -> str:
    text = json.dumps(value, ensure_ascii=False)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def describe_list_delta(name: str, expected: list, actual: list) -> str:
    """For measure_note_counts, point at the first differing measure, not the whole vector."""
    if name != "musicxml.measure_note_counts":
        return ""
    for index, (want, got) in enumerate(zip(expected, actual)):
        if want != got:
            return f" first divergence at measure {index}: {want} -> {got}"
    if len(expected) != len(actual):
        return f" length {len(expected)} -> {len(actual)} (measures added or dropped)"
    return ""


def load_run(run_id: str) -> dict[str, dict]:
    run_dir = REPORTS_DIR / run_id
    if not run_dir.is_dir():
        sys.exit(f"run not found: {run_dir}")
    reports = {}
    for path in sorted(run_dir.glob("*.json")):
        if path.name == "summary.json":
            continue
        reports[path.stem] = json.loads(path.read_text(encoding="utf-8"))
    if not reports:
        sys.exit(f"no fixture reports in {run_dir}")
    return reports


def load_golden() -> dict[str, dict]:
    if not GOLDEN_DIR.is_dir():
        return {}
    return {
        path.stem: json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(GOLDEN_DIR.glob("*.json"))
    }


def cmd_promote(run_id: str, only: list[str]) -> int:
    reports = load_run(run_id)
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    promoted = 0

    for fixture_id, report in sorted(reports.items()):
        if only and fixture_id not in only:
            continue
        if report.get("status") != "OK":
            print(f"  skip {fixture_id}: status={report.get('status')} — refusing to promote a failed run")
            continue

        golden = {
            "id": fixture_id,
            "promotedFrom": run_id,
            "promotedAt": report.get("recordedAt"),
            "pipeline": "legacy/pdf2pack.sh",
            "source": report.get("source"),
            "metrics": report.get("metrics"),
            "groundTruth": report.get("groundTruth"),
            "accuracy": report.get("accuracy"),
        }
        (GOLDEN_DIR / f"{fixture_id}.json").write_text(
            json.dumps(golden, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        promoted += 1
        print(f"  promoted {fixture_id}")

    print(f"\n{promoted} golden snapshot(s) written to {GOLDEN_DIR}")
    print("Review the diff and commit them — these become the regression contract.")
    return 0


def cmd_check(run_id: str, only: list[str]) -> int:
    reports = load_run(run_id)
    goldens = load_golden()

    if not goldens:
        print("no golden snapshots exist yet — run `promote` first")
        return 2

    regressions: list[str] = []
    unchecked: list[str] = []
    passed = 0

    for fixture_id, report in sorted(reports.items()):
        if only and fixture_id not in only:
            continue

        golden = goldens.get(fixture_id)
        if golden is None:
            unchecked.append(f"{fixture_id}: no golden snapshot")
            continue

        if report.get("status") != "OK":
            regressions.append(f"{fixture_id}: run status {report.get('status')} (golden was OK)")
            continue

        expected_metrics = golden.get("metrics", {})
        actual_metrics = report.get("metrics", {})
        differences: list[str] = []

        for field in COMPARED_FIELDS:
            expected = dig(expected_metrics, field)
            actual = dig(actual_metrics, field)
            if expected == actual:
                continue

            tolerance = TOLERANCE.get(field)
            if (
                tolerance is not None
                and isinstance(expected, (int, float))
                and isinstance(actual, (int, float))
                and abs(expected - actual) <= tolerance
            ):
                continue

            detail = ""
            if isinstance(expected, list) and isinstance(actual, list):
                detail = describe_list_delta(field, expected, actual)

            differences.append(
                f"    {field}: {summarise(expected)} -> {summarise(actual)}{detail}"
            )

        if differences:
            regressions.append(f"{fixture_id}:\n" + "\n".join(differences))
        else:
            passed += 1

    print(f"golden check — run {run_id}\n")
    print(f"  passed:     {passed}")
    print(f"  regressed:  {len(regressions)}")
    print(f"  unchecked:  {len(unchecked)}")

    if unchecked:
        print("\nUNCHECKED")
        for line in unchecked:
            print(f"  {line}")

    if regressions:
        print("\nREGRESSIONS")
        for line in regressions:
            print(f"  {line}")
        print(
            "\nIf a change is intentional and reviewed, re-promote:\n"
            f"  python3 tools/omr-baseline/harness/compare.py promote --run {run_id}"
        )
        return 1

    print("\nno regressions")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare a baseline run to the golden snapshots.")
    sub = parser.add_subparsers(dest="command", required=True)

    for name, help_text in (("check", "diff a run against golden"), ("promote", "write a run into golden")):
        sp = sub.add_parser(name, help=help_text)
        sp.add_argument("--run", required=True, help="run id under reports/")
        sp.add_argument("--id", action="append", default=[], help="limit to fixture id (repeatable)")

    args = parser.parse_args()
    if args.command == "promote":
        return cmd_promote(args.run, args.id)
    return cmd_check(args.run, args.id)


if __name__ == "__main__":
    raise SystemExit(main())
