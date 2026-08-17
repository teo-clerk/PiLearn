package org.pianoml.backend.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pianoml.backend.document.InvalidScoreDocumentException;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentService;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.omr.OmrJobStatus;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lifecycle tests for the worker → backend persistence loop.
 *
 * <p>The behaviours pinned here are the ones whose absence would corrupt learner state:
 * idempotency (a redelivered callback must not create a second revision), honest status
 * propagation (a partially-recognised score must never read as COMPLETED), and failure
 * containment (an invalid document must not be persisted).
 */
@ExtendWith(MockitoExtension.class)
class ScoreIngestionServiceTest {

  @Mock private OmrWorkerClient workerClient;
  @Mock private ScoreDocumentService documentService;
  @Mock private ScoreRepository scoreRepository;

  private ScoreIngestionService service;
  private UUID scoreId;
  private Score score;

  private static final String JOB_ID = "job_abc123";
  private static final String DOCUMENT_JSON = "{\"schema_version\":\"1.0\"}";

  @BeforeEach
  void setUp() {
    service = new ScoreIngestionService(workerClient, documentService, scoreRepository);
    scoreId = UUID.randomUUID();
    score = new Score();
    score.setId(scoreId);
    score.setTitle("Test Piece");
  }

  private OmrJobStatus status(String state, Integer sourcePages, Integer recognised,
                              List<Integer> dropped) {
    return new OmrJobStatus(
        JOB_ID, scoreId.toString(), state, "ANALYSE", 1.0, "done", 1,
        "2026-08-17T10:00:00Z", "2026-08-17T10:05:00Z",
        new OmrJobStatus.PageReconciliation(sourcePages, recognised, dropped, null),
        null, null, 1);
  }

  private ScoreDocumentEntity savedEntity(int revision, String reviewStatus,
                                          Integer sourcePages, Integer recognised) {
    var entity = new ScoreDocumentEntity();
    entity.setScoreId(scoreId);
    entity.setRevision(revision);
    entity.setReviewStatus(reviewStatus);
    entity.setDocumentConfidence(0.92);
    entity.setMeasureCount(48);
    entity.setGlobalGrade(5.5);
    entity.setSourcePages(sourcePages);
    entity.setRecognisedPages(recognised);
    return entity;
  }

  @Nested
  @DisplayName("happy path")
  class HappyPath {

    @Test
    @DisplayName("fetches, persists and links the document to the score")
    void ingestsCleanDocument() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 3, 3, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(scoreId.toString(), null))
          .thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(eq(scoreId), eq(DOCUMENT_JSON), any()))
          .thenReturn(savedEntity(1, "OK", 3, 3));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.kind()).isEqualTo(IngestionOutcome.Kind.INGESTED);
      assertThat(outcome.revision()).isEqualTo(1);
      assertThat(outcome.requiresReview()).isFalse();
      assertThat(outcome.hasDocument()).isTrue();

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository).save(captor.capture());
      Score persisted = captor.getValue();
      assertThat(persisted.getCurrentRevision()).isEqualTo(1);
      assertThat(persisted.getProcessingStatus())
          .isEqualTo(ScoreIngestionService.STATUS_COMPLETED);
      assertThat(persisted.getOmrJobId()).isEqualTo(JOB_ID);
      assertThat(persisted.getHasFiles()).isTrue();
    }

    @Test
    @DisplayName("copies measure count and grade onto the score for the library")
    void denormalisesSummaryFields() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 1, 1, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(any(), any(), any())).thenReturn(savedEntity(1, "OK", 1, 1));

      service.ingestCompletedJob(JOB_ID);

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository).save(captor.capture());
      assertThat(captor.getValue().getMeasuresCount()).isEqualTo(48);
      assertThat(captor.getValue().getGrade()).isEqualTo(5.5f);
      assertThat(captor.getValue().getDocumentConfidence()).isEqualTo(0.92);
    }
  }

  @Nested
  @DisplayName("partial recognition")
  class PartialRecognition {

    @Test
    @DisplayName("a score with dropped pages is stored but flagged REVIEW_REQUIRED")
    void droppedPagesForceReview() {
      // The legacy defect: 9 of 12 pages recognised, pipeline exited 0, and the short
      // score was treated as complete.
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("REVIEW_REQUIRED", 12, 9, List.of(7, 8, 9))));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(any(), any(), any()))
          .thenReturn(savedEntity(1, "REVIEW_REQUIRED", 12, 9));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.kind()).isEqualTo(IngestionOutcome.Kind.INGESTED);
      assertThat(outcome.requiresReview()).isTrue();

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository).save(captor.capture());
      assertThat(captor.getValue().getProcessingStatus())
          .isEqualTo(ScoreIngestionService.STATUS_REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("the document is still persisted so the user can review or correct it")
    void partialResultIsNotDiscarded() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("REVIEW_REQUIRED", 10, 8, List.of(3, 4))));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(any(), any(), any()))
          .thenReturn(savedEntity(1, "REVIEW_REQUIRED", 10, 8));

      service.ingestCompletedJob(JOB_ID);

      verify(documentService).save(eq(scoreId), eq(DOCUMENT_JSON), any());
    }
  }

  @Nested
  @DisplayName("idempotency")
  class Idempotency {

    @Test
    @DisplayName("a redelivered callback does not create a second revision")
    void redeliveryIsNoOp() {
      score.setOmrJobId(JOB_ID);
      score.setCurrentRevision(1);

      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 3, 3, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(documentService.find(scoreId, 1))
          .thenReturn(Optional.of(savedEntity(1, "OK", 3, 3)));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.kind()).isEqualTo(IngestionOutcome.Kind.ALREADY_INGESTED);
      assertThat(outcome.revision()).isEqualTo(1);
      assertThat(outcome.hasDocument()).isTrue();

      verify(documentService, never()).save(any(), any(), any());
      verify(workerClient, never()).fetchDocument(any(), any());
    }

    @Test
    @DisplayName("a DIFFERENT job for the same score does create a new revision")
    void reIngestionCreatesANewRevision() {
      score.setOmrJobId("job_older");
      score.setCurrentRevision(1);

      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 3, 3, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(any(), any(), any())).thenReturn(savedEntity(2, "OK", 3, 3));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.kind()).isEqualTo(IngestionOutcome.Kind.INGESTED);
      assertThat(outcome.revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("a score claiming a revision that is gone reports failure, not silence")
    void missingRevisionIsReported() {
      score.setOmrJobId(JOB_ID);
      score.setCurrentRevision(3);

      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 1, 1, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(documentService.find(scoreId, 3)).thenReturn(Optional.empty());

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("REVISION_MISSING");
    }
  }

  @Nested
  @DisplayName("failure handling")
  class Failures {

    @Test
    @DisplayName("an unknown job is reported, not thrown")
    void unknownJob() {
      when(workerClient.getJobStatus("nope")).thenReturn(Optional.empty());

      IngestionOutcome outcome = service.ingestCompletedJob("nope");

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("JOB_NOT_FOUND");
      verifyNoInteractions(scoreRepository);
    }

    @Test
    @DisplayName("a still-running job returns PENDING, which is not an error")
    void jobStillRunning() {
      var running = new OmrJobStatus(
          JOB_ID, scoreId.toString(), "RUNNING", "RECOGNISE", 0.4, "page 5/12", 1,
          "2026-08-17T10:00:00Z", "2026-08-17T10:02:00Z",
          new OmrJobStatus.PageReconciliation(12, null, List.of(), null), null, null, null);
      when(workerClient.getJobStatus(JOB_ID)).thenReturn(Optional.of(running));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.kind()).isEqualTo(IngestionOutcome.Kind.PENDING);
      assertThat(outcome.isFailure()).isFalse();
      verify(documentService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("a failed job marks the score FAILED and persists nothing")
    void failedJob() {
      var failed = new OmrJobStatus(
          JOB_ID, scoreId.toString(), "FAILED", "RECOGNISE", 0.5, "no page recognised", 3,
          "2026-08-17T10:00:00Z", "2026-08-17T10:05:00Z",
          new OmrJobStatus.PageReconciliation(12, 0, List.of(1, 2, 3), 0.0),
          "NO_PAGES_RECOGNISED", "no page could be recognised", null);
      when(workerClient.getJobStatus(JOB_ID)).thenReturn(Optional.of(failed));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("NO_PAGES_RECOGNISED");
      assertThat(score.getProcessingStatus()).isEqualTo(ScoreIngestionService.STATUS_FAILED);
      verify(documentService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("a completed job serving no document is a failure, not a success")
    void completedButNoDocument() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 1, 1, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.empty());

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("DOCUMENT_UNAVAILABLE");
      assertThat(score.getProcessingStatus()).isEqualTo(ScoreIngestionService.STATUS_FAILED);
    }

    @Test
    @DisplayName("an invalid document is rejected rather than persisted")
    void invalidDocumentIsNotPersisted() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 12, 9, List.of(7, 8, 9))));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
      when(workerClient.fetchDocument(any(), any())).thenReturn(Optional.of(DOCUMENT_JSON));
      when(documentService.save(any(), any(), any()))
          .thenThrow(new InvalidScoreDocumentException(
              "document claims status OK but only 9 of 12 pages were recognised"));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("INVALID_DOCUMENT");
      assertThat(outcome.errorDetail()).contains("9 of 12");
      assertThat(score.getProcessingStatus()).isEqualTo(ScoreIngestionService.STATUS_FAILED);
    }

    @Test
    @DisplayName("a deleted score is reported without a NullPointerException")
    void scoreDeletedMidFlight() {
      when(workerClient.getJobStatus(JOB_ID))
          .thenReturn(Optional.of(status("COMPLETED", 1, 1, List.of())));
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.empty());

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("SCORE_NOT_FOUND");
    }

    @Test
    @DisplayName("an unparseable scoreId is reported, not thrown")
    void unparseableScoreId() {
      var bad = new OmrJobStatus(
          JOB_ID, "not-a-uuid", "COMPLETED", "ANALYSE", 1.0, "done", 1,
          "2026-08-17T10:00:00Z", "2026-08-17T10:05:00Z",
          new OmrJobStatus.PageReconciliation(1, 1, List.of(), 1.0), null, null, 1);
      when(workerClient.getJobStatus(JOB_ID)).thenReturn(Optional.of(bad));

      IngestionOutcome outcome = service.ingestCompletedJob(JOB_ID);

      assertThat(outcome.isFailure()).isTrue();
      assertThat(outcome.errorCode()).isEqualTo("INVALID_SCORE_ID");
    }
  }

  @Nested
  @DisplayName("progress markers")
  class ProgressMarkers {

    @Test
    @DisplayName("markQueued records the job id so the callback can be matched")
    void marksQueued() {
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));

      service.markQueued(scoreId, JOB_ID);

      assertThat(score.getProcessingStatus()).isEqualTo(ScoreIngestionService.STATUS_QUEUED);
      assertThat(score.getOmrJobId()).isEqualTo(JOB_ID);
      verify(scoreRepository).save(score);
    }

    @Test
    @DisplayName("markRunning is a no-op for a score that no longer exists")
    void markRunningTolerantOfMissingScore() {
      when(scoreRepository.findById(scoreId)).thenReturn(Optional.empty());

      service.markRunning(scoreId);

      verify(scoreRepository, never()).save(any());
    }
  }
}
