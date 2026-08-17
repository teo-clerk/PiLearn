"""Job store tests — concurrency is the whole point.

The legacy path had no claim at all: `WorkloadProcessingService.processAllWorkloads()`
loaded every PENDING row and processed the lot in one execution, so two concurrent
triggers processed the same score twice (AUDIT §R2). These tests pin the replacement.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import pytest

from pilearn_worker.jobs import (
    MAX_ATTEMPTS,
    InMemoryJobStore,
    Job,
    JobStatus,
    make_idempotency_key,
    new_job_id,
)


def make_job(job_id: str | None = None, key: str = "hash:pipe:analysis") -> Job:
    return Job(
        id=job_id or new_job_id(),
        score_id="score-1",
        source_key=None,
        source_filename="test.pdf",
        title="Test",
        composer="Composer",
        make_fingering=False,
        idempotency_key=key,
    )


@pytest.fixture
def store() -> InMemoryJobStore:
    return InMemoryJobStore()


class TestAtomicClaim:
    def test_exactly_one_of_many_concurrent_claims_wins(self, store):
        """The property that makes double-processing impossible."""
        job = store.create(make_job())

        with ThreadPoolExecutor(max_workers=32) as pool:
            results = list(pool.map(lambda _: store.claim(job.id), range(32)))

        winners = [r for r in results if r is not None]
        assert len(winners) == 1, f"{len(winners)} workers claimed the same job"
        assert winners[0].status is JobStatus.RUNNING
        assert winners[0].attempt == 1

    def test_claiming_a_running_job_returns_none(self, store):
        job = store.create(make_job())
        assert store.claim(job.id) is not None
        assert store.claim(job.id) is None

    def test_claiming_a_terminal_job_returns_none(self, store):
        job = store.create(make_job())
        claimed = store.claim(job.id)
        claimed.status = JobStatus.COMPLETED
        store.update(claimed)

        assert store.claim(job.id) is None

    def test_claiming_an_unknown_job_returns_none(self, store):
        assert store.claim("job_nope") is None

    def test_attempt_increments_per_claim(self, store):
        job = store.create(make_job())

        first = store.claim(job.id)
        assert first.attempt == 1

        first.status = JobStatus.QUEUED
        store.update(first)

        second = store.claim(job.id)
        assert second.attempt == 2

    def test_claim_refused_past_the_attempt_budget(self, store):
        job = store.create(make_job())

        for _ in range(MAX_ATTEMPTS):
            claimed = store.claim(job.id)
            assert claimed is not None
            claimed.status = JobStatus.QUEUED
            store.update(claimed)

        assert store.claim(job.id) is None
        assert store.get(job.id).status is JobStatus.FAILED
        assert store.get(job.id).error_code == "MAX_ATTEMPTS_EXCEEDED"


class TestLease:
    def test_expired_lease_becomes_reclaimable(self, store):
        job = store.create(make_job())
        claimed = store.claim(job.id, lease_seconds=60)

        # Simulate the worker dying: the lease elapses with the job still RUNNING.
        claimed.lease_until = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
        store.update(claimed)

        assert store.get(job.id).is_lease_expired
        reclaimed = store.claim(job.id)
        assert reclaimed is not None
        assert reclaimed.attempt == 2

    def test_live_lease_blocks_reclaim(self, store):
        job = store.create(make_job())
        store.claim(job.id, lease_seconds=3600)

        assert store.claim(job.id) is None

    def test_sweeper_reports_reclaimed_jobs(self, store):
        job = store.create(make_job())
        claimed = store.claim(job.id, lease_seconds=60)
        claimed.lease_until = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
        store.update(claimed)

        assert store.sweep_expired_leases() == [job.id]
        assert store.get(job.id).status is JobStatus.LEASE_EXPIRED


class TestIdempotency:
    def test_identical_submissions_resolve_to_one_job(self, store):
        first = store.create(make_job(key="same-key"))
        second = store.create(make_job(key="same-key"))

        assert first.id == second.id

    def test_different_keys_create_different_jobs(self, store):
        first = store.create(make_job(key="key-a"))
        second = store.create(make_job(key="key-b"))

        assert first.id != second.id

    def test_key_changes_when_any_version_changes(self):
        base = make_idempotency_key("abc", "pipe-1", "analysis-1")

        assert make_idempotency_key("abc", "pipe-1", "analysis-1") == base
        assert make_idempotency_key("xyz", "pipe-1", "analysis-1") != base
        assert make_idempotency_key("abc", "pipe-2", "analysis-1") != base
        assert make_idempotency_key("abc", "pipe-1", "analysis-2") != base

    def test_concurrent_creates_with_one_key_yield_one_job(self, store):
        with ThreadPoolExecutor(max_workers=16) as pool:
            jobs = list(pool.map(lambda _: store.create(make_job(key="race")), range(16)))

        assert len({j.id for j in jobs}) == 1


class TestSerialisation:
    def test_round_trip_preserves_state(self):
        job = make_job()
        job.status = JobStatus.REVIEW_REQUIRED
        job.dropped_pages = (7, 8)
        job.source_pages = 12
        job.recognised_pages = 10
        job.progress.stage = "RECOGNISE"
        job.progress.percentage = 0.55

        restored = Job.from_dict(job.to_dict())

        assert restored.status is JobStatus.REVIEW_REQUIRED
        assert restored.dropped_pages == (7, 8)
        assert restored.source_pages == 12
        assert restored.progress.stage == "RECOGNISE"
        assert restored.progress.percentage == 0.55


def test_terminal_status_classification():
    assert JobStatus.COMPLETED.is_terminal
    assert JobStatus.FAILED.is_terminal
    assert JobStatus.REVIEW_REQUIRED.is_terminal
    assert not JobStatus.QUEUED.is_terminal
    assert not JobStatus.RUNNING.is_terminal
    assert not JobStatus.LEASE_EXPIRED.is_terminal
