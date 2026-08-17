"""Pipeline runner: PDF -> validated OMR output, with strict page accounting.

This is the strangler shell around the legacy pipeline. Stages still shell out to the
same scripts (`pdf2pack.sh` and friends) in the first increment; what changes is that
every page is accounted for and no failure is silent.

The defect being fixed
----------------------
`pdf2pack.sh` runs homr per page and only fails when EVERY page fails:

    if [ "$HOMR_TOTAL" -gt 0 ] && [ "$HOMR_SUCCESSES" -eq 0 ]; then exit 1; fi

A 12-page score whose page 7 fails silently becomes an 11-page score. The measures are
gone, the exit code is 0, and every downstream consumer — including a learning roadmap —
treats the truncated result as complete.

The fix has three independent layers, so no single future change can reintroduce it:

  1. Here: source page count is read from the PDF itself and reconciled against the OMR
     result. A mismatch is recorded per page, with a reason.
  2. `PageAccounting.status` -> PARTIAL_FAILURE, which forces REVIEW_REQUIRED.
  3. `ScoreDocument.validate_consistency` refuses to construct a document that claims
     status OK while carrying dropped pages.
"""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path

logger = logging.getLogger(__name__)

# The legacy pipeline is slow: 300 dpi rasterisation, per-page transformer OMR, several
# MuseScore round-trips. A 40-page score can legitimately take the best part of an hour.
DEFAULT_TIMEOUT_SEC = 3600

# homr's own summary line, which the legacy script prints:
#   "homr summary: 9 success(es), 3 error(s), 12 file(s) processed"
_HOMR_SUMMARY = re.compile(
    r"homr summary:\s*(\d+)\s*success\(es\),\s*(\d+)\s*error\(s\),\s*(\d+)\s*file"
)
_HOMR_FAILURE = re.compile(r"Warning: homr failed for '([^']+)'")


class PipelineStatus(str, Enum):
    SUCCESS = "SUCCESS"
    PARTIAL_FAILURE = "PARTIAL_FAILURE"
    FAILED = "FAILED"


class PipelineStage(str, Enum):
    INTAKE = "INTAKE"
    RASTERISE = "RASTERISE"
    RECOGNISE = "RECOGNISE"
    MERGE = "MERGE"
    NORMALISE = "NORMALISE"
    VALIDATE = "VALIDATE"
    ENRICH = "ENRICH"
    BUILD = "BUILD"
    ANALYSE = "ANALYSE"

    @property
    def progress(self) -> float:
        """Fraction complete once this stage finishes. Drives the UI progress bar."""
        return {
            PipelineStage.INTAKE: 0.05,
            PipelineStage.RASTERISE: 0.15,
            PipelineStage.RECOGNISE: 0.55,   # the long pole
            PipelineStage.MERGE: 0.62,
            PipelineStage.NORMALISE: 0.70,
            PipelineStage.VALIDATE: 0.75,
            PipelineStage.ENRICH: 0.85,
            PipelineStage.BUILD: 0.93,
            PipelineStage.ANALYSE: 1.00,
        }[self]


class IssueCode(str, Enum):
    PAGE_DROPPED = "PAGE_DROPPED"
    RASTERISE_FAILED = "RASTERISE_FAILED"
    NO_PAGES_RECOGNISED = "NO_PAGES_RECOGNISED"
    ARCHIVE_MISSING = "ARCHIVE_MISSING"
    SCRIPT_FAILED = "SCRIPT_FAILED"
    TIMEOUT = "TIMEOUT"
    SOURCE_UNREADABLE = "SOURCE_UNREADABLE"


@dataclass(frozen=True, slots=True)
class PipelineIssue:
    code: IssueCode
    severity: str                   # ERROR | WARNING
    detail: str
    page: int | None = None
    stage: PipelineStage | None = None


@dataclass(frozen=True, slots=True)
class PageOutcome:
    page: int
    recognised: bool
    reason: str | None = None


@dataclass(frozen=True, slots=True)
class PageAccounting:
    """Reconciliation of source pages against recognised pages.

    Invariant: `len(outcomes) == source_pages`. Every page in the source PDF gets a row,
    whether it succeeded or not. That is the whole point — the legacy pipeline produced
    rows only for successes.
    """

    source_pages: int
    outcomes: tuple[PageOutcome, ...]

    def __post_init__(self) -> None:
        if len(self.outcomes) != self.source_pages:
            raise ValueError(
                f"page accounting is incomplete: {len(self.outcomes)} outcomes for "
                f"{self.source_pages} source pages. Every page must be accounted for."
            )

    @property
    def recognised_pages(self) -> int:
        return sum(1 for o in self.outcomes if o.recognised)

    @property
    def dropped_pages(self) -> tuple[int, ...]:
        return tuple(o.page for o in self.outcomes if not o.recognised)

    @property
    def status(self) -> PipelineStatus:
        if self.recognised_pages == 0:
            return PipelineStatus.FAILED
        if self.dropped_pages:
            return PipelineStatus.PARTIAL_FAILURE
        return PipelineStatus.SUCCESS

    @property
    def coverage(self) -> float:
        return self.recognised_pages / self.source_pages if self.source_pages else 0.0


@dataclass(slots=True)
class PipelineResult:
    status: PipelineStatus
    stage_reached: PipelineStage
    accounting: PageAccounting | None
    archive_path: Path | None
    issues: list[PipelineIssue] = field(default_factory=list)
    duration_sec: float = 0.0
    stdout: str = ""
    stderr: str = ""

    @property
    def ok(self) -> bool:
        return self.status is PipelineStatus.SUCCESS

    @property
    def usable(self) -> bool:
        """Partial results are still worth showing the user behind a review gate."""
        return self.status in (PipelineStatus.SUCCESS, PipelineStatus.PARTIAL_FAILURE)


ProgressCallback = Callable[[PipelineStage, float, str], None]


def count_pdf_pages(pdf_path: Path) -> int:
    """Authoritative source page count.

    Tries pypdf, then the poppler CLI. Both are present in the worker image; the fallback
    exists because the harness also runs in the legacy toolchain container, where pypdf
    may not be installed.
    """
    try:
        from pypdf import PdfReader

        return len(PdfReader(str(pdf_path)).pages)
    except ImportError:
        pass
    except Exception as exc:
        raise PipelineError(
            IssueCode.SOURCE_UNREADABLE, f"pypdf could not read {pdf_path.name}: {exc}"
        ) from exc

    pdfinfo = shutil.which("pdfinfo")
    if not pdfinfo:
        raise PipelineError(
            IssueCode.SOURCE_UNREADABLE,
            "cannot determine page count: neither pypdf nor pdfinfo is available",
        )

    completed = subprocess.run(
        [pdfinfo, str(pdf_path)], capture_output=True, text=True, check=False
    )
    if completed.returncode != 0:
        raise PipelineError(
            IssueCode.SOURCE_UNREADABLE, f"pdfinfo failed: {completed.stderr.strip()}"
        )

    for line in completed.stdout.splitlines():
        if line.startswith("Pages:"):
            return int(line.split()[1])

    raise PipelineError(
        IssueCode.SOURCE_UNREADABLE, "pdfinfo output contained no page count"
    )


class PipelineError(RuntimeError):
    def __init__(self, code: IssueCode, detail: str, page: int | None = None) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.page = page


def parse_recognition_log(stdout: str, source_pages: int) -> PageAccounting:
    """Reconcile the OMR log against the known source page count.

    This is where the silent-drop defect dies. The legacy script's own summary line tells
    us how many pages failed; we combine it with the authoritative source count so that
    every page — including ones the script never mentioned — gets an outcome row.
    """
    failed_pages: set[int] = set()

    for match in _HOMR_FAILURE.finditer(stdout):
        filename = match.group(1)
        page_match = re.search(r"(\d+)(?=\.png$)", filename)
        if page_match:
            failed_pages.add(int(page_match.group(1)))

    summary = _HOMR_SUMMARY.search(stdout)
    if summary:
        successes, errors, processed = (int(g) for g in summary.groups())

        if processed < source_pages:
            # The script never even attempted some pages — the most dangerous case,
            # because nothing in its own logs records it.
            for page in range(processed + 1, source_pages + 1):
                failed_pages.add(page)

        # Error count and named failures disagree: trust the count, invent placeholders
        # for the unnamed ones rather than under-reporting.
        if errors > len(failed_pages):
            for page in range(1, source_pages + 1):
                if len(failed_pages) >= errors:
                    break
                failed_pages.add(page)

    outcomes = tuple(
        PageOutcome(
            page=page,
            recognised=page not in failed_pages,
            reason="OMR engine reported a failure for this page"
            if page in failed_pages
            else None,
        )
        for page in range(1, source_pages + 1)
    )

    return PageAccounting(source_pages=source_pages, outcomes=outcomes)


class LegacyPipelineRunner:
    """Runs the legacy shell pipeline with page accounting bolted on.

    Increment 1 of the strangler (PHASE2_KICKOFF §2.2 step 1): behaviour is deliberately
    identical to the legacy scripts so the OMR baseline harness stays green. The only
    additions are observation and validation — no recognition logic changes here.
    """

    def __init__(
        self,
        pipeline_root: Path,
        work_root: Path,
        timeout_sec: int = DEFAULT_TIMEOUT_SEC,
    ) -> None:
        self.pipeline_root = Path(pipeline_root)
        self.work_root = Path(work_root)
        self.timeout_sec = timeout_sec

    def script_for(self, suffix: str) -> Path:
        mapping = {
            ".pdf": "pdf2pack.sh",
            ".png": "image2pack.sh",
            ".jpg": "image2pack.sh",
            ".jpeg": "image2pack.sh",
            ".musicxml": "musicxml2pack.sh",
            ".xml": "musicxml2pack.sh",
            ".mxl": "musicxml2pack.sh",
            ".mid": "midi2pack.sh",
            ".midi": "midi2pack.sh",
        }
        name = mapping.get(suffix.lower())
        if not name:
            raise PipelineError(
                IssueCode.SOURCE_UNREADABLE, f"unsupported input type: {suffix}"
            )
        return self.pipeline_root / "scripts" / name

    def run(
        self,
        job_id: str,
        source: Path,
        title: str,
        composer: str,
        make_fingering: bool = False,
        on_progress: ProgressCallback | None = None,
    ) -> PipelineResult:
        started = time.monotonic()
        issues: list[PipelineIssue] = []

        def report(stage: PipelineStage, message: str) -> None:
            if on_progress:
                on_progress(stage, stage.progress, message)

        report(PipelineStage.INTAKE, "validating input")

        suffix = source.suffix.lower()
        try:
            script = self.script_for(suffix)
            source_pages = count_pdf_pages(source) if suffix == ".pdf" else 1
        except PipelineError as exc:
            return PipelineResult(
                status=PipelineStatus.FAILED,
                stage_reached=PipelineStage.INTAKE,
                accounting=None,
                archive_path=None,
                issues=[PipelineIssue(exc.code, "ERROR", exc.detail, stage=PipelineStage.INTAKE)],
                duration_sec=time.monotonic() - started,
            )

        if not script.is_file():
            return PipelineResult(
                status=PipelineStatus.FAILED,
                stage_reached=PipelineStage.INTAKE,
                accounting=None,
                archive_path=None,
                issues=[
                    PipelineIssue(
                        IssueCode.SCRIPT_FAILED,
                        "ERROR",
                        f"pipeline script not found: {script}",
                        stage=PipelineStage.INTAKE,
                    )
                ],
                duration_sec=time.monotonic() - started,
            )

        # Stage the input under a sanitised name. Real corpus filenames contain spaces,
        # accents and ampersands, which the legacy scripts' unquoted globs cannot survive
        # (AUDIT §S3). The `upload_` prefix mirrors what PackService does in production.
        work_dir = self.work_root / job_id
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)

        staged = work_dir / f"upload_{job_id}{suffix}"
        shutil.copy2(source, staged)
        expected_archive = work_dir / f"{job_id}.zip"

        report(PipelineStage.RASTERISE, f"processing {source_pages} page(s)")

        argv = [
            str(script),
            str(staged),
            title,
            composer,
            "true" if make_fingering else "",
        ]

        try:
            completed = subprocess.run(
                argv,
                cwd=str(self.pipeline_root),
                capture_output=True,
                text=True,
                timeout=self.timeout_sec,
                check=False,
            )
            stdout, stderr, exit_code = completed.stdout, completed.stderr, completed.returncode
        except subprocess.TimeoutExpired as exc:
            return PipelineResult(
                status=PipelineStatus.FAILED,
                stage_reached=PipelineStage.RECOGNISE,
                accounting=None,
                archive_path=None,
                issues=[
                    PipelineIssue(
                        IssueCode.TIMEOUT,
                        "ERROR",
                        f"pipeline exceeded {self.timeout_sec}s",
                        stage=PipelineStage.RECOGNISE,
                    )
                ],
                duration_sec=time.monotonic() - started,
                stdout=_decode(exc.stdout),
                stderr=_decode(exc.stderr),
            )

        report(PipelineStage.RECOGNISE, "reconciling pages")

        accounting = parse_recognition_log(stdout, source_pages)

        for page in accounting.dropped_pages:
            issues.append(
                PipelineIssue(
                    code=IssueCode.PAGE_DROPPED,
                    severity="ERROR",
                    detail=(
                        f"page {page} of {source_pages} was not recognised; its measures "
                        "are absent from the score"
                    ),
                    page=page,
                    stage=PipelineStage.RECOGNISE,
                )
            )

        if accounting.recognised_pages == 0:
            issues.append(
                PipelineIssue(
                    IssueCode.NO_PAGES_RECOGNISED,
                    "ERROR",
                    "no page could be recognised",
                    stage=PipelineStage.RECOGNISE,
                )
            )
            return PipelineResult(
                status=PipelineStatus.FAILED,
                stage_reached=PipelineStage.RECOGNISE,
                accounting=accounting,
                archive_path=None,
                issues=issues,
                duration_sec=time.monotonic() - started,
                stdout=stdout,
                stderr=stderr,
            )

        if exit_code != 0:
            issues.append(
                PipelineIssue(
                    IssueCode.SCRIPT_FAILED,
                    "ERROR",
                    f"pipeline script exited {exit_code}",
                    stage=PipelineStage.NORMALISE,
                )
            )

        if not expected_archive.is_file():
            issues.append(
                PipelineIssue(
                    IssueCode.ARCHIVE_MISSING,
                    "ERROR",
                    f"expected archive not produced: {expected_archive.name}",
                    stage=PipelineStage.NORMALISE,
                )
            )
            return PipelineResult(
                status=PipelineStatus.FAILED,
                stage_reached=PipelineStage.NORMALISE,
                accounting=accounting,
                archive_path=None,
                issues=issues,
                duration_sec=time.monotonic() - started,
                stdout=stdout,
                stderr=stderr,
            )

        report(PipelineStage.VALIDATE, "validating output")

        status = accounting.status
        if status is PipelineStatus.PARTIAL_FAILURE:
            logger.warning(
                "job %s: PARTIAL_FAILURE — %d/%d pages recognised, dropped %s",
                job_id,
                accounting.recognised_pages,
                accounting.source_pages,
                accounting.dropped_pages,
            )

        return PipelineResult(
            status=status,
            stage_reached=PipelineStage.VALIDATE,
            accounting=accounting,
            archive_path=expected_archive,
            issues=issues,
            duration_sec=time.monotonic() - started,
            stdout=stdout,
            stderr=stderr,
        )


def _decode(value: bytes | str | None) -> str:
    if value is None:
        return ""
    return value.decode(errors="replace") if isinstance(value, bytes) else value
