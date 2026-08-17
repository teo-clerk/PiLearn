"""PiLearn OMR worker — FastAPI entrypoint.

Endpoints
---------
    GET  /health                                liveness + readiness
    POST /api/v1/omr/process                    submit a job (multipart file or s3Key)
    GET  /api/v1/omr/jobs/{job_id}              status, progress, page reconciliation
    GET  /api/v1/scores/{score_id}/document     the built ScoreDocument

Concurrency model
-----------------
Ingestion is one long CPU-bound subprocess per job, not a fan-out of small tasks. So:

  * jobs run in a bounded thread pool, sized from OMR_MAX_CONCURRENCY;
  * the semaphore is what protects the box — homr holds a torch model in memory and
    MuseScore spawns an offscreen Qt process, so unbounded concurrency OOMs the container
    long before it saturates the CPU;
  * job state lives in Redis when configured, so the API replica that accepts a job need
    not be the one that ran it.

There is deliberately no Celery. A broker plus a worker pool would add an operational
component that buys nothing for single-subprocess jobs on a scale-to-zero platform.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import shutil
import tempfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any, Literal

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, HTTPException, UploadFile, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, model_validator

from pilearn_worker import __version__
from pilearn_worker.config import Settings, get_settings
from pilearn_worker.jobs import (
    InMemoryJobStore,
    Job,
    JobStatus,
    JobStore,
    RedisJobStore,
    make_idempotency_key,
    new_job_id,
)
from pilearn_worker.pipeline.runner import (
    LegacyPipelineRunner,
    PipelineStage,
    PipelineStatus,
)
from pilearn_worker.storage import DocumentStore, ObjectStorage

logger = logging.getLogger(__name__)

MAX_UPLOAD_BYTES = 50 * 1024 * 1024
ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg", ".musicxml", ".xml", ".mxl", ".mid", ".midi"}
PDF_MAGIC = b"%PDF-"


# ─────────────────────────────────────────────────────────────────────────────
# Wire models
# ─────────────────────────────────────────────────────────────────────────────

class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    version: str
    checks: dict[str, bool]
    detail: dict[str, str] = Field(default_factory=dict)


class ProcessRequest(BaseModel):
    """JSON body for the s3Key path. The multipart path uses Form fields instead."""

    score_id: str
    s3_key: str = Field(alias="s3Key")
    title: str
    composer: str = "Unknown"
    make_fingering: bool = Field(default=False, alias="makeFingering")

    model_config = {"populate_by_name": True}


class SubmitResponse(BaseModel):
    job_id: str = Field(alias="jobId")
    score_id: str = Field(alias="scoreId")
    status: JobStatus
    deduplicated: bool = Field(
        default=False,
        description="True when an identical submission was already in flight.",
    )

    model_config = {"populate_by_name": True}


class PageReconciliation(BaseModel):
    source_pages: int | None = Field(default=None, alias="sourcePages")
    recognised_pages: int | None = Field(default=None, alias="recognisedPages")
    dropped_pages: list[int] = Field(default_factory=list, alias="droppedPages")
    coverage: float | None = None

    model_config = {"populate_by_name": True}


class JobStatusResponse(BaseModel):
    job_id: str = Field(alias="jobId")
    score_id: str = Field(alias="scoreId")
    status: JobStatus
    stage: str
    progress: float = Field(ge=0.0, le=1.0)
    message: str
    attempt: int
    created_at: str = Field(alias="createdAt")
    updated_at: str = Field(alias="updatedAt")
    pages: PageReconciliation
    error_code: str | None = Field(default=None, alias="errorCode")
    error_detail: str | None = Field(default=None, alias="errorDetail")
    document_revision: int | None = Field(default=None, alias="documentRevision")

    model_config = {"populate_by_name": True}

    @classmethod
    def from_job(cls, job: Job) -> JobStatusResponse:
        coverage = None
        if job.source_pages and job.recognised_pages is not None:
            coverage = round(job.recognised_pages / job.source_pages, 4)
        return cls(
            jobId=job.id,
            scoreId=job.score_id,
            status=job.effective_status,
            stage=job.progress.stage,
            progress=job.progress.percentage,
            message=job.progress.message,
            attempt=job.attempt,
            createdAt=job.created_at,
            updatedAt=job.updated_at,
            pages=PageReconciliation(
                sourcePages=job.source_pages,
                recognisedPages=job.recognised_pages,
                droppedPages=list(job.dropped_pages),
                coverage=coverage,
            ),
            errorCode=job.error_code,
            errorDetail=job.error_detail,
            documentRevision=job.document_revision,
        )


class ErrorResponse(BaseModel):
    code: str
    message: str
    detail: dict[str, Any] = Field(default_factory=dict)


# ─────────────────────────────────────────────────────────────────────────────
# Application state
# ─────────────────────────────────────────────────────────────────────────────

class WorkerState:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.job_store: JobStore = self._build_job_store(settings)
        self.storage = ObjectStorage(settings)
        self.documents = DocumentStore(self.storage, settings)
        self.runner = LegacyPipelineRunner(
            pipeline_root=Path(settings.pipeline_root),
            work_root=Path(settings.work_root),
            timeout_sec=settings.job_timeout_sec,
        )
        self.executor = ThreadPoolExecutor(
            max_workers=settings.omr_max_concurrency, thread_name_prefix="omr"
        )
        self.semaphore = asyncio.Semaphore(settings.omr_max_concurrency)

    @staticmethod
    def _build_job_store(settings: Settings) -> JobStore:
        if not settings.redis_url:
            logger.warning(
                "REDIS_URL not set — using the in-memory job store. "
                "Correct for a single replica only; job state is lost on restart."
            )
            return InMemoryJobStore()
        import redis

        return RedisJobStore(redis.Redis.from_url(settings.redis_url))

    def shutdown(self) -> None:
        self.executor.shutdown(wait=True, cancel_futures=False)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    app.state.worker = WorkerState(settings)
    Path(settings.work_root).mkdir(parents=True, exist_ok=True)
    logger.info(
        "worker %s ready — concurrency=%d, pipeline_root=%s",
        __version__,
        settings.omr_max_concurrency,
        settings.pipeline_root,
    )
    try:
        yield
    finally:
        app.state.worker.shutdown()


app = FastAPI(
    title="PiLearn OMR Worker",
    version=__version__,
    lifespan=lifespan,
    responses={500: {"model": ErrorResponse}},
)


def get_state() -> WorkerState:
    return app.state.worker


StateDep = Annotated[WorkerState, Depends(get_state)]


# ─────────────────────────────────────────────────────────────────────────────
# Health
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/health", response_model=HealthResponse, tags=["ops"])
def health(state: StateDep) -> HealthResponse:
    """Liveness and readiness in one probe.

    Liveness is implicit — a response at all means the process is up. Readiness is the
    `checks` map: a false entry means the container is running but cannot do useful work,
    which is exactly what an orchestrator needs to know before routing traffic.
    """
    checks: dict[str, bool] = {}
    detail: dict[str, str] = {}

    pipeline_root = Path(state.settings.pipeline_root)
    scripts_present = (pipeline_root / "scripts" / "pdf2pack.sh").is_file()
    checks["pipeline_scripts"] = scripts_present
    if not scripts_present:
        detail["pipeline_scripts"] = f"pdf2pack.sh not found under {pipeline_root}"

    for tool in ("musescore3", "pdftoppm"):
        present = shutil.which(tool) is not None
        checks[tool] = present
        if not present:
            detail[tool] = f"{tool} not on PATH"

    work_writable = False
    try:
        work_root = Path(state.settings.work_root)
        work_root.mkdir(parents=True, exist_ok=True)
        probe = work_root / ".health"
        probe.write_text("ok")
        probe.unlink()
        work_writable = True
    except OSError as exc:
        detail["work_root"] = str(exc)
    checks["work_root_writable"] = work_writable

    if state.settings.redis_url:
        try:
            import redis

            redis.Redis.from_url(state.settings.redis_url).ping()
            checks["redis"] = True
        except Exception as exc:
            checks["redis"] = False
            detail["redis"] = str(exc)

    return HealthResponse(
        status="ok" if all(checks.values()) else "degraded",
        version=__version__,
        checks=checks,
        detail=detail,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Submission
# ─────────────────────────────────────────────────────────────────────────────

def _validate_upload(filename: str, payload: bytes) -> str:
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail={
                "code": "UNSUPPORTED_TYPE",
                "message": f"unsupported file type '{suffix}'",
                "detail": {"allowed": sorted(ALLOWED_SUFFIXES)},
            },
        )
    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={
                "code": "FILE_TOO_LARGE",
                "message": f"file exceeds {MAX_UPLOAD_BYTES // (1024 * 1024)} MB",
                "detail": {"size": len(payload)},
            },
        )
    if not payload:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            detail={"code": "EMPTY_FILE", "message": "uploaded file is empty", "detail": {}},
        )
    # Magic-byte check: an extension is a claim, not evidence.
    if suffix == ".pdf" and not payload.startswith(PDF_MAGIC):
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "NOT_A_PDF",
                "message": "file has a .pdf extension but is not a PDF",
                "detail": {},
            },
        )
    return suffix


@app.post(
    "/api/v1/omr/process",
    response_model=SubmitResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["omr"],
)
async def submit(
    state: StateDep,
    background: BackgroundTasks,
    file: Annotated[UploadFile | None, File()] = None,
    score_id: Annotated[str | None, Form(alias="scoreId")] = None,
    s3_key: Annotated[str | None, Form(alias="s3Key")] = None,
    title: Annotated[str | None, Form()] = None,
    composer: Annotated[str, Form()] = "Unknown",
    make_fingering: Annotated[bool, Form(alias="makeFingering")] = False,
) -> SubmitResponse:
    """Submit a score for ingestion.

    Accepts either a multipart `file` or an `s3Key` pointing at already-uploaded bytes.
    Returns 202 with a job id; poll `/api/v1/omr/jobs/{job_id}` for progress.

    Idempotent on (content hash, pipeline version, analysis version): resubmitting the
    same bytes under the same tool versions returns the in-flight job rather than
    starting a second one.
    """
    if not score_id:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "MISSING_SCORE_ID", "message": "scoreId is required", "detail": {}},
        )
    if (file is None) == (s3_key is None):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "code": "INVALID_SOURCE",
                "message": "provide exactly one of 'file' or 's3Key'",
                "detail": {},
            },
        )

    work_root = Path(state.settings.work_root)
    work_root.mkdir(parents=True, exist_ok=True)

    if file is not None:
        payload = await file.read()
        suffix = _validate_upload(file.filename or "upload.pdf", payload)
        filename = file.filename or f"upload{suffix}"
    else:
        try:
            payload = state.storage.get_object(s3_key)  # type: ignore[arg-type]
        except FileNotFoundError as exc:
            raise HTTPException(
                status.HTTP_404_NOT_FOUND,
                detail={
                    "code": "SOURCE_NOT_FOUND",
                    "message": f"object not found: {s3_key}",
                    "detail": {"s3Key": s3_key},
                },
            ) from exc
        filename = Path(s3_key).name  # type: ignore[arg-type]
        suffix = _validate_upload(filename, payload)

    input_hash = hashlib.sha256(payload).hexdigest()
    idempotency_key = make_idempotency_key(
        input_hash, state.settings.pipeline_version, state.settings.analysis_version
    )

    existing = state.job_store.find_by_idempotency_key(idempotency_key)
    if existing and not existing.status.is_terminal:
        return SubmitResponse(
            jobId=existing.id,
            scoreId=existing.score_id,
            status=existing.status,
            deduplicated=True,
        )

    job_id = new_job_id()
    staged = work_root / f"source_{job_id}{suffix}"
    staged.write_bytes(payload)

    job = Job(
        id=job_id,
        score_id=score_id,
        source_key=s3_key,
        source_filename=filename,
        title=title or Path(filename).stem,
        composer=composer,
        make_fingering=make_fingering,
        idempotency_key=idempotency_key,
    )
    job = state.job_store.create(job)

    if job.id != job_id:
        # A concurrent identical submission won the race; drop our staged copy.
        staged.unlink(missing_ok=True)
        return SubmitResponse(
            jobId=job.id, scoreId=job.score_id, status=job.status, deduplicated=True
        )

    background.add_task(_run_job, state, job.id, staged)
    logger.info("job %s queued for score %s (%s)", job.id, score_id, filename)

    return SubmitResponse(jobId=job.id, scoreId=job.score_id, status=job.status)


@app.get("/api/v1/omr/jobs/{job_id}", response_model=JobStatusResponse, tags=["omr"])
def job_status(job_id: str, state: StateDep) -> JobStatusResponse:
    job = state.job_store.get(job_id)
    if job is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            detail={"code": "JOB_NOT_FOUND", "message": f"no job {job_id}", "detail": {}},
        )
    return JobStatusResponse.from_job(job)


@app.get("/api/v1/scores/{score_id}/document", tags=["scores"])
def get_document(score_id: str, state: StateDep, revision: int | None = None) -> JSONResponse:
    """Return the canonical ScoreDocument.

    Immutable per (score_id, revision), so it is safe to cache aggressively.
    """
    document = state.documents.load(score_id, revision)
    if document is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            detail={
                "code": "DOCUMENT_NOT_FOUND",
                "message": f"no ScoreDocument for score {score_id}",
                "detail": {"scoreId": score_id, "revision": revision},
            },
        )
    return JSONResponse(
        content=document,
        headers={"Cache-Control": "public, max-age=31536000, immutable"},
    )


# ─────────────────────────────────────────────────────────────────────────────
# Execution
# ─────────────────────────────────────────────────────────────────────────────

async def _run_job(state: WorkerState, job_id: str, source: Path) -> None:
    """Claim, execute, record. Never raises — a crashed task must still update the job."""
    async with state.semaphore:
        job = state.job_store.claim(job_id, lease_seconds=state.settings.lease_seconds)
        if job is None:
            logger.info("job %s already claimed or not claimable — skipping", job_id)
            return

        def progress(stage: PipelineStage, pct: float, message: str) -> None:
            current = state.job_store.get(job_id)
            if current is None:
                return
            current.progress.stage = stage.value
            current.progress.percentage = pct
            current.progress.message = message
            state.job_store.update(current)

        try:
            loop = asyncio.get_running_loop()
            result = await loop.run_in_executor(
                state.executor,
                lambda: state.runner.run(
                    job_id=job_id,
                    source=source,
                    title=job.title,
                    composer=job.composer,
                    make_fingering=job.make_fingering,
                    on_progress=progress,
                ),
            )

            job = state.job_store.get(job_id) or job

            if result.accounting is not None:
                job.source_pages = result.accounting.source_pages
                job.recognised_pages = result.accounting.recognised_pages
                job.dropped_pages = result.accounting.dropped_pages

            if result.status is PipelineStatus.FAILED:
                first_error = next((i for i in result.issues if i.severity == "ERROR"), None)
                job.status = JobStatus.FAILED
                job.error_code = first_error.code.value if first_error else "PIPELINE_FAILED"
                job.error_detail = first_error.detail if first_error else "pipeline failed"
            elif result.status is PipelineStatus.PARTIAL_FAILURE:
                # Usable, but never presented as complete. A dropped page means missing
                # measures, and a roadmap built on those teaches the wrong bars.
                job.status = JobStatus.REVIEW_REQUIRED
                job.error_code = "PAGE_DROPPED"
                job.error_detail = (
                    f"{len(result.accounting.dropped_pages)} of "
                    f"{result.accounting.source_pages} pages were not recognised: "
                    f"{list(result.accounting.dropped_pages)}"
                )
            else:
                job.status = JobStatus.COMPLETED
                job.progress.percentage = 1.0
                job.progress.message = "complete"

            job.lease_until = None
            state.job_store.update(job)
            logger.info(
                "job %s finished: %s in %.1fs", job_id, job.status.value, result.duration_sec
            )

        except Exception as exc:
            logger.exception("job %s crashed", job_id)
            current = state.job_store.get(job_id)
            if current is not None:
                current.status = JobStatus.FAILED
                current.error_code = "WORKER_EXCEPTION"
                current.error_detail = f"{type(exc).__name__}: {exc}"
                current.lease_until = None
                state.job_store.update(current)
        finally:
            source.unlink(missing_ok=True)


def run() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "pilearn_worker.main:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    run()
