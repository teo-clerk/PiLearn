"""Page accounting — the tests that matter most in Phase 2.

The legacy pipeline dropped OMR-failed pages silently and exited 0. These tests pin the
replacement behaviour: every source page gets an outcome row, a drop forces
PARTIAL_FAILURE, and an incomplete reconciliation cannot be constructed at all.
"""

from __future__ import annotations

import pytest

from pilearn_worker.pipeline.runner import (
    PageAccounting,
    PageOutcome,
    PipelineStatus,
    parse_recognition_log,
)


class TestPageAccountingInvariant:
    def test_rejects_incomplete_accounting(self):
        """The core guard: you cannot report on 9 pages when the source had 12."""
        with pytest.raises(ValueError, match="page accounting is incomplete"):
            PageAccounting(
                source_pages=12,
                outcomes=tuple(
                    PageOutcome(page=p, recognised=True) for p in range(1, 10)
                ),
            )

    def test_accepts_complete_accounting(self):
        accounting = PageAccounting(
            source_pages=3,
            outcomes=tuple(PageOutcome(page=p, recognised=True) for p in (1, 2, 3)),
        )
        assert accounting.status is PipelineStatus.SUCCESS
        assert accounting.coverage == 1.0
        assert accounting.dropped_pages == ()

    def test_partial_failure_when_a_page_is_dropped(self):
        accounting = PageAccounting(
            source_pages=3,
            outcomes=(
                PageOutcome(page=1, recognised=True),
                PageOutcome(page=2, recognised=False, reason="homr failed"),
                PageOutcome(page=3, recognised=True),
            ),
        )
        assert accounting.status is PipelineStatus.PARTIAL_FAILURE
        assert accounting.dropped_pages == (2,)
        assert accounting.recognised_pages == 2
        assert accounting.coverage == pytest.approx(2 / 3)

    def test_total_failure_when_no_page_recognised(self):
        accounting = PageAccounting(
            source_pages=2,
            outcomes=(
                PageOutcome(page=1, recognised=False, reason="x"),
                PageOutcome(page=2, recognised=False, reason="y"),
            ),
        )
        assert accounting.status is PipelineStatus.FAILED
        assert accounting.coverage == 0.0


class TestParseRecognitionLog:
    def test_all_pages_succeed(self):
        stdout = "homr summary: 3 success(es), 0 error(s), 3 file(s) processed"
        accounting = parse_recognition_log(stdout, source_pages=3)

        assert accounting.status is PipelineStatus.SUCCESS
        assert accounting.dropped_pages == ()
        assert len(accounting.outcomes) == 3

    def test_named_page_failure_is_attributed(self):
        stdout = (
            "starting homr page-01.png\n"
            "starting homr page-02.png\n"
            "Warning: homr failed for 'page-02.png' (exit code: 1)\n"
            "starting homr page-03.png\n"
            "homr summary: 2 success(es), 1 error(s), 3 file(s) processed"
        )
        accounting = parse_recognition_log(stdout, source_pages=3)

        assert accounting.status is PipelineStatus.PARTIAL_FAILURE
        assert accounting.dropped_pages == (2,)
        assert accounting.outcomes[1].reason is not None

    def test_pages_never_attempted_are_counted_as_dropped(self):
        """The most dangerous case: the script processed fewer pages than the PDF has,
        and its own logs say nothing about the missing ones."""
        stdout = "homr summary: 9 success(es), 0 error(s), 9 file(s) processed"
        accounting = parse_recognition_log(stdout, source_pages=12)

        assert accounting.status is PipelineStatus.PARTIAL_FAILURE
        assert accounting.dropped_pages == (10, 11, 12)
        assert accounting.recognised_pages == 9

    def test_error_count_exceeding_named_failures_is_not_under_reported(self):
        """homr reported 3 errors but only named one. Trust the count."""
        stdout = (
            "Warning: homr failed for 'page-02.png' (exit code: 1)\n"
            "homr summary: 2 success(es), 3 error(s), 5 file(s) processed"
        )
        accounting = parse_recognition_log(stdout, source_pages=5)

        assert len(accounting.dropped_pages) == 3
        assert 2 in accounting.dropped_pages
        assert accounting.status is PipelineStatus.PARTIAL_FAILURE

    def test_no_summary_line_means_every_page_assumed_recognised(self):
        """A script that printed no summary is not evidence of failure — but the
        accounting still covers every page, so nothing is unaccounted for."""
        accounting = parse_recognition_log("", source_pages=4)

        assert len(accounting.outcomes) == 4
        assert accounting.status is PipelineStatus.SUCCESS

    def test_single_page_source(self):
        stdout = "homr summary: 1 success(es), 0 error(s), 1 file(s) processed"
        accounting = parse_recognition_log(stdout, source_pages=1)

        assert accounting.status is PipelineStatus.SUCCESS
        assert accounting.outcomes[0].page == 1

    def test_legacy_defect_scenario_is_now_caught(self):
        """Regression pin for the exact legacy behaviour.

        pdf2pack.sh exits 0 here — 9 of 12 pages succeeded, and its guard only fires when
        successes == 0. The resulting score is missing three pages of music and the old
        pipeline reported complete success.
        """
        stdout = (
            "Warning: homr failed for 'page-07.png' (exit code: 1)\n"
            "Warning: homr failed for 'page-08.png' (exit code: 1)\n"
            "Warning: homr failed for 'page-09.png' (exit code: 1)\n"
            "homr summary: 9 success(es), 3 error(s), 12 file(s) processed"
        )
        accounting = parse_recognition_log(stdout, source_pages=12)

        assert accounting.status is PipelineStatus.PARTIAL_FAILURE, (
            "a 12-page score that recognised only 9 pages must never report success"
        )
        assert accounting.dropped_pages == (7, 8, 9)
        assert accounting.coverage == pytest.approx(0.75)
