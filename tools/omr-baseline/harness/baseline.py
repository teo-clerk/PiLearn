#!/usr/bin/env python3
"""Run the CURRENT (legacy) OMR pipeline over the fixture corpus and record what it produces.

Purpose: establish a measured baseline before Phase 2 replaces the bash/ProcessBuilder
orchestration with a typed FastAPI worker. Without this, every pipeline change during
the migration is an unverifiable guess.

This script deliberately does NOT fix the legacy pipeline. It measures it as-is,
including its defects. The only accommodation is filename sanitisation (see below),
because the corpus filenames contain spaces and accents that the unquoted globs in
pdf2pack.sh cannot survive — and that is a separately tracked bug (AUDIT §S3), not
the thing we are trying to measure here.

Runs INSIDE the OMR toolchain container:

    docker compose --profile omr up -d
    docker compose exec omr ~/shared-venv/bin/python \\
        omr-baseline/harness/baseline.py --all

Output: one JSON report per fixture in reports/<run-id>/, plus a run summary.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import extract_all  # noqa: E402

HARNESS_DIR = Path(__file__).resolve().parent
BASELINE_DIR = HARNESS_DIR.parent
MANIFEST_PATH = BASELINE_DIR / "fixtures" / "manifest.json"
REPORTS_DIR = BASELINE_DIR / "reports"

# Layout inside the OMR toolchain image.
PIPELINE_ROOT = Path(os.environ.get("PIPELINE_ROOT", "/home/appuser"))
PDF2PACK = PIPELINE_ROOT / "scripts" / "pdf2pack.sh"
MUSICXML2PACK = PIPELINE_ROOT / "scripts" / "musicxml2pack.sh"

# The legacy pipeline is slow: 300 dpi rasterisation, per-page transformer OMR,
# several MuseScore round-trips. 40 pages can legitimately take many minutes.
DEFAULT_TIMEOUT_SEC = 3600


def load_manifest() -> dict:
    if not MANIFEST_PATH.is_file():
        sys.exit(f"manifest not found: {MANIFEST_PATH}")
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def resolve_fixture(entry: dict, corpus_dir: Path) -> Path | None:
    candidate = corpus_dir / entry["file"]
    return candidate if candidate.is_file() else None


def sha256_of(path: Path) -> str:
    import hashlib

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_pipeline(fixture_id: str, source_pdf: Path, entry: dict, work_root: Path) -> dict:
    """Invoke the legacy pack script exactly as PackService does, and capture everything."""
    work_dir = work_root / fixture_id
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)

    # PackService writes its temp file as `upload_<id>.<ext>`; pdf2pack.sh derives the
    # output root by stripping the extension and the `upload_` prefix. Reproduce that
    # exactly, and sanitise the name so the script's unquoted globs survive (AUDIT §S3).
    staged_pdf = work_dir / f"upload_{fixture_id}.pdf"
    shutil.copy2(source_pdf, staged_pdf)
    expected_zip = work_dir / f"{fixture_id}.zip"

    script = str(PDF2PACK)
    argv = [
        script,
        str(staged_pdf),
        entry.get("title", fixture_id),
        entry.get("composer", "Unknown"),
        "true" if entry.get("makeFingering", False) else "",
    ]

    started = time.monotonic()
    try:
        completed = subprocess.run(
            argv,
            cwd=str(PIPELINE_ROOT),
            capture_output=True,
            text=True,
            timeout=DEFAULT_TIMEOUT_SEC,
            check=False,
        )
        exit_code = completed.returncode
        stdout, stderr = completed.stdout, completed.stderr
        timed_out = False
    except subprocess.TimeoutExpired as exc:
        exit_code = -1
        stdout = exc.stdout.decode() if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        stderr = exc.stderr.decode() if isinstance(exc.stderr, bytes) else (exc.stderr or "")
        timed_out = True

    duration = time.monotonic() - started

    return {
        "exitCode": exit_code,
        "timedOut": timed_out,
        "durationSec": round(duration, 2),
        "expectedZip": str(expected_zip),
        "stdout": stdout,
        "stderr": stderr,
        "omr": parse_homr_summary(stdout),
    }


def parse_homr_summary(stdout: str) -> dict:
    """Recover per-page OMR outcomes from the script's own log line.

    pdf2pack.sh prints: `homr summary: N success(es), M error(s), T file(s) processed`
    and only fails the run when EVERY page fails — so a partially recognised score is
    reported as success today. Capturing these counts is how we detect that.
    """
    summary = {"pagesTotal": None, "pagesRecognised": None, "pagesFailed": None,
               "failedPages": [], "silentPartialFailure": False}

    for line in stdout.splitlines():
        stripped = line.strip()
        if stripped.startswith("homr summary:"):
            tokens = stripped.replace(",", " ").split()
            numbers = [int(t) for t in tokens if t.isdigit()]
            if len(numbers) >= 3:
                summary["pagesRecognised"], summary["pagesFailed"], summary["pagesTotal"] = numbers[:3]
        elif stripped.startswith("Warning: homr failed for"):
            summary["failedPages"].append(stripped)

    failed = summary.get("pagesFailed") or 0
    if failed > 0:
        # The pipeline exited 0 but dropped pages: measures are missing from the score
        # and nothing downstream knows. This is the defect DATA_PIPELINE §P2 fixes.
        summary["silentPartialFailure"] = True

    return summary


def process_fixture(entry: dict, corpus_dir: Path, work_root: Path, run_id: str) -> dict:
    fixture_id = entry["id"]
    print(f"[{fixture_id}] starting", flush=True)

    source = resolve_fixture(entry, corpus_dir)
    if source is None:
        print(f"[{fixture_id}] SKIP — file not found: {entry['file']}", flush=True)
        return {"id": fixture_id, "status": "MISSING", "file": entry["file"]}

    actual_sha = sha256_of(source)
    expected_sha = entry.get("sha256")
    sha_ok = expected_sha in (None, "", actual_sha)
    if not sha_ok:
        print(f"[{fixture_id}] WARNING — checksum mismatch; corpus drifted", flush=True)

    run = run_pipeline(fixture_id, source, entry, work_root)
    extract_dir = work_root / fixture_id / "extracted"
    metrics = extract_all(Path(run["expectedZip"]), extract_dir)

    produced = metrics.archive.exists
    status = "OK" if (run["exitCode"] == 0 and produced) else "FAILED"

    report = {
        "id": fixture_id,
        "status": status,
        "runId": run_id,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "source": {
            "file": entry["file"],
            "sha256": actual_sha,
            "sha256Matches": sha_ok,
            "pages": entry.get("pages"),
        },
        # Non-deterministic; reported, never compared.
        "execution": {
            "exitCode": run["exitCode"],
            "timedOut": run["timedOut"],
            "durationSec": run["durationSec"],
            "omr": run["omr"],
        },
        # Deterministic; this is what the golden diff compares.
        "metrics": metrics.to_dict(),
        "groundTruth": entry.get("groundTruth"),
        "accuracy": score_against_ground_truth(metrics, entry.get("groundTruth")),
    }

    log_dir = REPORTS_DIR / run_id / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    (log_dir / f"{fixture_id}.stdout.log").write_text(run["stdout"], encoding="utf-8")
    (log_dir / f"{fixture_id}.stderr.log").write_text(run["stderr"], encoding="utf-8")

    marker = "ok" if status == "OK" else "FAILED"
    print(
        f"[{fixture_id}] {marker} in {run['durationSec']}s — "
        f"{metrics.musicxml.measure_count} measures, {metrics.musicxml.note_count} notes",
        flush=True,
    )
    return report


def score_against_ground_truth(metrics, ground_truth: dict | None) -> dict | None:
    """Compare recognised structure to hand-entered truth from the printed score.

    Regression testing only needs run-to-run stability. Knowing whether the pipeline is
    any GOOD needs a human to read the actual score once. Fields are optional; only what
    is filled in gets scored.
    """
    if not ground_truth:
        return None

    result: dict = {}

    expected_measures = ground_truth.get("measureCount")
    if expected_measures:
        actual = metrics.musicxml.measure_count
        result["measureCount"] = {
            "expected": expected_measures,
            "actual": actual,
            "delta": actual - expected_measures,
            "accuracy": round(1 - abs(actual - expected_measures) / expected_measures, 4),
        }

    expected_key = ground_truth.get("keySignature")
    if expected_key is not None:
        actual_key = metrics.musicxml.key_signatures[0] if metrics.musicxml.key_signatures else None
        result["keySignature"] = {
            "expected": str(expected_key),
            "actual": actual_key,
            "match": str(expected_key) == actual_key,
        }

    expected_time = ground_truth.get("timeSignature")
    if expected_time is not None:
        actual_time = metrics.musicxml.time_signatures[0] if metrics.musicxml.time_signatures else None
        result["timeSignature"] = {
            "expected": expected_time,
            "actual": actual_time,
            "match": expected_time == actual_time,
        }

    expected_staves = ground_truth.get("staffCount")
    if expected_staves:
        result["staffCount"] = {
            "expected": expected_staves,
            "actual": metrics.musicxml.staff_count,
            "match": expected_staves == metrics.musicxml.staff_count,
        }

    scored = [v for v in result.values() if "accuracy" in v or "match" in v]
    if scored:
        values = [v.get("accuracy", 1.0 if v.get("match") else 0.0) for v in scored]
        result["overall"] = round(sum(values) / len(values), 4)

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the legacy OMR pipeline over the fixture corpus.")
    parser.add_argument("--all", action="store_true", help="run every fixture in the manifest")
    parser.add_argument("--id", action="append", default=[], help="run a specific fixture id (repeatable)")
    parser.add_argument("--corpus", default=None, help="directory holding the fixture PDFs")
    parser.add_argument("--work", default=None, help="scratch directory for pipeline output")
    parser.add_argument("--run-id", default=None, help="label for this run (default: UTC timestamp)")
    args = parser.parse_args()

    if not args.all and not args.id:
        parser.error("pass --all or at least one --id")

    manifest = load_manifest()
    corpus_dir = Path(args.corpus or manifest.get("corpusDir", "/home/appuser/fixtures"))
    work_root = Path(args.work or "/home/appuser/work/baseline")
    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    if not PDF2PACK.is_file():
        sys.exit(
            f"pipeline script not found: {PDF2PACK}\n"
            "This harness must run inside the OMR toolchain container:\n"
            "  docker compose --profile omr up -d\n"
            "  docker compose exec omr ~/shared-venv/bin/python omr-baseline/harness/baseline.py --all"
        )
    if not corpus_dir.is_dir():
        sys.exit(f"corpus directory not found: {corpus_dir}")

    fixtures = manifest["fixtures"]
    if args.id:
        wanted = set(args.id)
        fixtures = [f for f in fixtures if f["id"] in wanted]
        missing = wanted - {f["id"] for f in fixtures}
        if missing:
            sys.exit(f"unknown fixture id(s): {', '.join(sorted(missing))}")

    run_dir = REPORTS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    work_root.mkdir(parents=True, exist_ok=True)

    print(f"run {run_id} — {len(fixtures)} fixture(s) from {corpus_dir}\n", flush=True)

    reports = []
    for entry in fixtures:
        try:
            report = process_fixture(entry, corpus_dir, work_root, run_id)
        except Exception as exc:  # a harness crash must not lose the completed fixtures
            print(f"[{entry['id']}] HARNESS ERROR: {type(exc).__name__}: {exc}", flush=True)
            report = {"id": entry["id"], "status": "HARNESS_ERROR", "error": f"{type(exc).__name__}: {exc}"}
        reports.append(report)
        (run_dir / f"{report['id']}.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
        )

    ok = sum(1 for r in reports if r.get("status") == "OK")
    partial = sum(
        1 for r in reports
        if r.get("execution", {}).get("omr", {}).get("silentPartialFailure")
    )
    summary = {
        "runId": run_id,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "pipeline": "legacy/pdf2pack.sh",
        "fixtureCount": len(reports),
        "ok": ok,
        "failed": len(reports) - ok,
        "silentPartialFailures": partial,
        "totalDurationSec": round(
            sum(r.get("execution", {}).get("durationSec", 0) for r in reports), 2
        ),
        "fixtures": [
            {
                "id": r["id"],
                "status": r.get("status"),
                "measures": r.get("metrics", {}).get("musicxml", {}).get("measure_count"),
                "notes": r.get("metrics", {}).get("musicxml", {}).get("note_count"),
                "accuracy": (r.get("accuracy") or {}).get("overall"),
            }
            for r in reports
        ],
    }
    (run_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print(f"\n{ok}/{len(reports)} ok · {partial} silent partial failure(s)")
    print(f"reports: {run_dir}")
    return 0 if ok == len(reports) else 1


if __name__ == "__main__":
    raise SystemExit(main())
