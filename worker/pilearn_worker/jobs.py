"""Job state and the in-process store.

Two backends behind one interface:

  * `InMemoryJobStore`  — tests and single-instance local development.
  * `RedisJobStore`     — anything with more than one worker replica.

The atomic-claim contract is what matters. The legacy path had no claim at all:
`WorkloadProcessingService.processAllWorkloads()` loaded every PENDING row and processed
them in one execution, so two concurrent triggers processed the same score twice
(AUDIT §R2). `claim()` here is compare-and-set: exactly one caller can move a job out of
QUEUED, and everyone else gets None.
"""

from __future__ import annotations

import json
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Protocol

# How long a claimed job may run before another worker may reclaim it. Sized for the
# slowest realistic ingestion (a 40-page score through 300 dpi OMR).
DEFAULT_LEASE_SECONDS = 900
MAX_ATTEMPTS = 3


class JobStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    FAILED = "FAILED"
    LEASE_EXPIRED = "LEASE_EXPIRED"

    @property
    def is_terminal(self) -> bool:
        return self in (JobStatus.COMPLETED, JobStatus.REVIEW_REQUIRED, JobStatus.FAILED)


@dataclass(slots=True)
class JobProgress:
    stage: str = "INTAKE"
    percentage: float = 0.0
    message: str = "queued"
    updated_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())


@dataclass(slots=True)
class Job:
    id: str
    score_id: str
    source_key: str | None
    source_filename: str
    title: str
    composer: str
    make_fingering: bool
    idempotency_key: str

    status: JobStatus = JobStatus.QUEUED
    attempt: int = 0
    progress: JobProgress = field(default_factory=JobProgress)

    created_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    updated_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    lease_until: str | None = None

    error_code: str | None = None
    error_detail: str | None = None
    document_revision: int | None = None

    # Page reconciliation, surfaced to the client so a partial result is never silent.
    source_pages: int | None = None
    recognised_pages: int | None = None
    dropped_pages: tuple[int, ...] = ()

    def to_dict(self) -> dict:
        payload = asdict(self)
        payload["status"] = self.status.value
        payload["dropped_pages"] = list(self.dropped_pages)
        return payload

    @classmethod
    def from_dict(cls, payload: dict) -> Job:
        data = dict(payload)
        data["status"] = JobStatus(data["status"])
        data["dropped_pages"] = tuple(data.get("dropped_pages") or ())
        progress = data.get("progress") or {}
        data["progress"] = JobProgress(**progress) if isinstance(progress, dict) else progress
        return cls(**data)

    @property
    def is_lease_expired(self) -> bool:
        if self.status is not JobStatus.RUNNING or not self.lease_until:
            return False
        return datetime.fromisoformat(self.lease_until) < datetime.now(UTC)

    @property
    def effective_status(self) -> JobStatus:
        """Status as an observer should see it, without mutating stored state.

        `get()` deliberately does not write. An earlier version flipped RUNNING to
        LEASE_EXPIRED on read, which made `is_lease_expired` self-negating: the first
        read cleared the very condition the next read was meant to observe. Expiry is
        materialised only by `claim()` and `sweep_expired_leases()`.
        """
        return JobStatus.LEASE_EXPIRED if self.is_lease_expired else self.status


class JobStore(Protocol):
    def create(self, job: Job) -> Job: ...
    def get(self, job_id: str) -> Job | None: ...
    def find_by_idempotency_key(self, key: str) -> Job | None: ...
    def claim(self, job_id: str, lease_seconds: int = DEFAULT_LEASE_SECONDS) -> Job | None: ...
    def update(self, job: Job) -> Job: ...
    def sweep_expired_leases(self) -> list[str]: ...


def make_idempotency_key(input_hash: str, pipeline_version: str, analysis_version: str) -> str:
    """Resubmitting the same bytes under the same tool versions returns the same job."""
    return f"{input_hash}:{pipeline_version}:{analysis_version}"


def new_job_id() -> str:
    return f"job_{uuid.uuid4().hex[:16]}"


class InMemoryJobStore:
    """Single-process store. Correct under threads, useless across replicas."""

    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._by_key: dict[str, str] = {}
        self._lock = threading.RLock()

    def create(self, job: Job) -> Job:
        with self._lock:
            existing_id = self._by_key.get(job.idempotency_key)
            if existing_id and existing_id in self._jobs:
                return self._jobs[existing_id]
            self._jobs[job.id] = job
            self._by_key[job.idempotency_key] = job.id
            return job

    def get(self, job_id: str) -> Job | None:
        # Pure read. Use `job.effective_status` to see expiry without materialising it.
        with self._lock:
            return self._jobs.get(job_id)

    def find_by_idempotency_key(self, key: str) -> Job | None:
        with self._lock:
            job_id = self._by_key.get(key)
            return self._jobs.get(job_id) if job_id else None

    def claim(self, job_id: str, lease_seconds: int = DEFAULT_LEASE_SECONDS) -> Job | None:
        """Compare-and-set: QUEUED|LEASE_EXPIRED -> RUNNING. Exactly one caller wins."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            if job.is_lease_expired:
                job.status = JobStatus.LEASE_EXPIRED
            if job.status not in (JobStatus.QUEUED, JobStatus.LEASE_EXPIRED):
                return None
            if job.attempt >= MAX_ATTEMPTS:
                job.status = JobStatus.FAILED
                job.error_code = "MAX_ATTEMPTS_EXCEEDED"
                job.error_detail = f"job failed after {job.attempt} attempts"
                return None

            job.status = JobStatus.RUNNING
            job.attempt += 1
            job.lease_until = (
                datetime.now(UTC) + timedelta(seconds=lease_seconds)
            ).isoformat()
            job.updated_at = datetime.now(UTC).isoformat()
            return job

    def update(self, job: Job) -> Job:
        with self._lock:
            job.updated_at = datetime.now(UTC).isoformat()
            self._jobs[job.id] = job
            return job

    def sweep_expired_leases(self) -> list[str]:
        with self._lock:
            reclaimed = []
            for job in self._jobs.values():
                if job.is_lease_expired:
                    job.status = JobStatus.LEASE_EXPIRED
                    job.lease_until = None
                    job.updated_at = datetime.now(UTC).isoformat()
                    reclaimed.append(job.id)
            return reclaimed


class RedisJobStore:
    """Multi-replica store. `claim` is a Lua script so the CAS is genuinely atomic."""

    _CLAIM_SCRIPT = """
    local raw = redis.call('GET', KEYS[1])
    if not raw then return nil end
    local job = cjson.decode(raw)
    local now, lease, max_attempts = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])

    if job.status == 'RUNNING' and job.lease_until and job.lease_until < now then
        job.status = 'LEASE_EXPIRED'
    end
    if job.status ~= 'QUEUED' and job.status ~= 'LEASE_EXPIRED' then return nil end
    if job.attempt >= max_attempts then
        job.status = 'FAILED'
        job.error_code = 'MAX_ATTEMPTS_EXCEEDED'
        redis.call('SET', KEYS[1], cjson.encode(job))
        return nil
    end

    job.status = 'RUNNING'
    job.attempt = job.attempt + 1
    job.lease_until = ARGV[4]
    job.updated_at = now
    redis.call('SET', KEYS[1], cjson.encode(job))
    return cjson.encode(job)
    """

    def __init__(self, client, namespace: str = "pilearn:job", ttl_seconds: int = 7 * 86400):
        self._redis = client
        self._ns = namespace
        self._ttl = ttl_seconds
        self._claim = client.register_script(self._CLAIM_SCRIPT)

    def _key(self, job_id: str) -> str:
        return f"{self._ns}:{job_id}"

    def _idem_key(self, key: str) -> str:
        return f"{self._ns}:idem:{key}"

    def create(self, job: Job) -> Job:
        idem = self._idem_key(job.idempotency_key)
        # SETNX: the first submission wins; later identical ones resolve to it.
        if not self._redis.set(idem, job.id, nx=True, ex=self._ttl):
            existing_id = self._redis.get(idem)
            if existing_id:
                existing = self.get(
                    existing_id.decode() if isinstance(existing_id, bytes) else existing_id
                )
                if existing:
                    return existing
        self._redis.set(self._key(job.id), json.dumps(job.to_dict()), ex=self._ttl)
        return job

    def get(self, job_id: str) -> Job | None:
        raw = self._redis.get(self._key(job_id))
        if not raw:
            return None
        return Job.from_dict(json.loads(raw))

    def find_by_idempotency_key(self, key: str) -> Job | None:
        job_id = self._redis.get(self._idem_key(key))
        if not job_id:
            return None
        return self.get(job_id.decode() if isinstance(job_id, bytes) else job_id)

    def claim(self, job_id: str, lease_seconds: int = DEFAULT_LEASE_SECONDS) -> Job | None:
        now = datetime.now(UTC)
        raw = self._claim(
            keys=[self._key(job_id)],
            args=[
                now.isoformat(),
                lease_seconds,
                MAX_ATTEMPTS,
                (now + timedelta(seconds=lease_seconds)).isoformat(),
            ],
        )
        if not raw:
            return None
        return Job.from_dict(json.loads(raw))

    def update(self, job: Job) -> Job:
        job.updated_at = datetime.now(UTC).isoformat()
        self._redis.set(self._key(job.id), json.dumps(job.to_dict()), ex=self._ttl)
        return job

    def sweep_expired_leases(self) -> list[str]:
        reclaimed = []
        for key in self._redis.scan_iter(match=f"{self._ns}:job_*"):
            raw = self._redis.get(key)
            if not raw:
                continue
            job = Job.from_dict(json.loads(raw))
            if job.is_lease_expired:
                job.status = JobStatus.LEASE_EXPIRED
                job.lease_until = None
                self.update(job)
                reclaimed.append(job.id)
        return reclaimed
