package org.pianoml.backend.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentService;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.omr.OmrJobStatus;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;
import org.pianoml.backend.storage.ScoreStorageService;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Status, document and MusicXML endpoints.
 *
 * <p>The contract that matters to the client is the status vocabulary: it polls until a
 * terminal state and must never be told READY for a score whose pages were dropped.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoreStatusControllerTest {

  @Mock private ScoreRepository scoreRepository;
  @Mock private ScoreDocumentService documentService;
  @Mock private ScoreStorageService storageService;
  @Mock private OmrWorkerClient workerClient;
  @Mock private ScoreIngestionService ingestionService;

  private ScoreStatusController controller;
  private UUID scoreId;
  private Score score;

  @BeforeEach
  void setUp() {
    controller = new ScoreStatusController(
        scoreRepository, documentService, storageService, workerClient, ingestionService);
    scoreId = UUID.randomUUID();
    score = new Score();
    score.setId(scoreId);
    when(scoreRepository.findById(scoreId)).thenReturn(Optional.of(score));
  }

  private ScoreDocumentEntity document(int revision, String review, Integer source, Integer got) {
    var entity = new ScoreDocumentEntity();
    entity.setScoreId(scoreId);
    entity.setRevision(revision);
    entity.setReviewStatus(review);
    entity.setSourcePages(source);
    entity.setRecognisedPages(got);
    return entity;
  }

  private OmrJobStatus job(String status, String stage, double progress,
                           Integer source, Integer got, List<Integer> dropped) {
    return new OmrJobStatus("job_1", scoreId.toString(), status, stage, progress, "working", 1,
        "2026-08-18T10:00:00Z", "2026-08-18T10:01:00Z",
        new OmrJobStatus.PageReconciliation(source, got, dropped, null), null, null, 1);
  }

  @Nested
  @DisplayName("status")
  class Status {

    @Test
    @DisplayName("a completed score reports READY and is terminal")
    void completedIsReady() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_COMPLETED);
      score.setCurrentRevision(1);
      when(documentService.findLatest(scoreId))
          .thenReturn(Optional.of(document(1, "OK", 3, 3)));

      var body = controller.status(scoreId).getBody();

      assertThat(body).isNotNull();
      assertThat(body.status()).isEqualTo(ScoreStatusResponse.READY);
      assertThat(body.isTerminal()).isTrue();
      assertThat(body.warningCount()).isZero();
      // A finished score must not put load on the worker.
      verify(workerClient, never()).getJobStatus(any());
    }

    @Test
    @DisplayName("a worker that has finished has its document pulled into our database")
    void finishedJobIsPulledAcross() {
      // The worker's output only reaches us when something pulls it. Where the worker
      // cannot call back, nothing did: polling reached READY, the client opened the
      // practice surface, and every artefact 404'd because no revision existed.
      score.setProcessingStatus(ScoreIngestionService.STATUS_RUNNING);
      score.setOmrJobId("job_1");
      score.setCurrentRevision(null);
      when(workerClient.getJobStatus("job_1"))
          .thenReturn(Optional.of(job("COMPLETED", "VALIDATE", 1.0, 1, 1, List.of())));

      when(ingestionService.ingestCompletedJob(eq("job_1"), eq(scoreId))).thenAnswer(invocation -> {
        // Stand in for the write the real service performs.
        score.setCurrentRevision(1);
        score.setProcessingStatus(ScoreIngestionService.STATUS_COMPLETED);
        when(documentService.findLatest(scoreId))
            .thenReturn(Optional.of(document(1, "OK", 1, 1)));
        return IngestionOutcome.ingested(
            "job_1", scoreId.toString(), document(1, "OK", 1, 1), false);
      });

      var body = controller.status(scoreId).getBody();

      verify(ingestionService).ingestCompletedJob("job_1", scoreId);
      assertThat(body).isNotNull();
      // READY must mean the document is actually there, not merely that the worker said so.
      assertThat(body.status()).isEqualTo(ScoreStatusResponse.READY);
      assertThat(body.revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("a score already pulled across is not pulled again")
    void alreadyIngestedIsNotRepulled() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_COMPLETED);
      score.setCurrentRevision(1);
      when(documentService.findLatest(scoreId))
          .thenReturn(Optional.of(document(1, "OK", 1, 1)));

      controller.status(scoreId);

      verify(ingestionService, never()).ingestCompletedJob(any(), any());
    }

    @Test
    @DisplayName("a failed job is never pulled across")
    void failedJobIsNotPulled() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_RUNNING);
      score.setOmrJobId("job_1");
      when(workerClient.getJobStatus("job_1"))
          .thenReturn(Optional.of(job("FAILED", "RECOGNISE", 0.5, 1, 0, List.of())));

      var body = controller.status(scoreId).getBody();

      verify(ingestionService, never()).ingestCompletedJob(any(), any());
      assertThat(body).isNotNull();
      assertThat(body.status()).isEqualTo(ScoreStatusResponse.FAILED);
    }

    @Test
    @DisplayName("dropped pages report REVIEW_REQUIRED with a warning count, never READY")
    void droppedPagesNeverReportReady() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_COMPLETED);
      when(documentService.findLatest(scoreId))
          .thenReturn(Optional.of(document(1, "REVIEW_REQUIRED", 12, 9)));

      var body = controller.status(scoreId).getBody();

      assertThat(body.status()).isEqualTo(ScoreStatusResponse.REVIEW_REQUIRED);
      assertThat(body.warningCount()).isEqualTo(3);
      assertThat(body.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a failed score reports FAILED")
    void failedScore() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_FAILED);

      var body = controller.status(scoreId).getBody();

      assertThat(body.status()).isEqualTo(ScoreStatusResponse.FAILED);
      assertThat(body.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("an in-flight job reports live worker progress")
    void inFlightUsesWorker() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_RUNNING);
      score.setOmrJobId("job_1");
      when(workerClient.getJobStatus("job_1"))
          .thenReturn(Optional.of(job("RUNNING", "RECOGNISE", 0.42, 12, null, List.of())));

      var body = controller.status(scoreId).getBody();

      assertThat(body.status()).isEqualTo(ScoreStatusResponse.PROCESSING);
      assertThat(body.stage()).isEqualTo("RECOGNISE");
      assertThat(body.progress()).isEqualTo(0.42);
      assertThat(body.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("an unreachable worker degrades to the stored status, not an error")
    void workerUnreachableDegrades() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_RUNNING);
      score.setOmrJobId("job_1");
      when(workerClient.getJobStatus("job_1")).thenReturn(Optional.empty());

      var body = controller.status(scoreId).getBody();

      // Erroring here would make the client abandon a job that is still running fine.
      assertThat(body.status()).isEqualTo(ScoreStatusResponse.PROCESSING);
      assertThat(body.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("a queued score with no job yet reports QUEUED")
    void queuedBeforeJobExists() {
      score.setProcessingStatus(ScoreIngestionService.STATUS_QUEUED);

      var body = controller.status(scoreId).getBody();

      assertThat(body.status()).isEqualTo(ScoreStatusResponse.QUEUED);
    }

    @Test
    @DisplayName("an unknown score is 404")
    void unknownScore() {
      UUID missing = UUID.randomUUID();
      when(scoreRepository.findById(missing)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> controller.status(missing))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("404");
    }
  }

  @Nested
  @DisplayName("document and musicxml")
  class Artefacts {

    @Test
    @DisplayName("serves the stored document JSON unchanged")
    void servesDocument() {
      when(documentService.findDocumentJson(scoreId, null))
          .thenReturn(Optional.of("{\"schema_version\":\"1.0\"}"));

      var response = controller.document(scoreId, null);

      assertThat(response.getBody()).isEqualTo("{\"schema_version\":\"1.0\"}");
      assertThat(response.getHeaders().getCacheControl()).contains("immutable");
    }

    @Test
    @DisplayName("404 when no document has been produced yet")
    void documentMissing() {
      when(documentService.findDocumentJson(scoreId, null)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> controller.document(scoreId, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("404");
    }

    @Test
    @DisplayName("serves the alignment index")
    void servesIndex() {
      when(documentService.findAlignmentIndexJson(scoreId, null))
          .thenReturn(Optional.of("{\"ppq\":480}"));

      assertThat(controller.alignmentIndex(scoreId, null).getBody()).isEqualTo("{\"ppq\":480}");
    }

    @Test
    @DisplayName("streams MusicXML for the score's current revision")
    void servesMusicXml() {
      score.setCurrentRevision(2);
      when(storageService.getDerived(scoreId, 2, "score.musicxml"))
          .thenReturn(Optional.of("<score-partwise/>".getBytes(StandardCharsets.UTF_8)));

      assertThat(controller.musicXml(scoreId, null).getBody()).isEqualTo("<score-partwise/>");
    }

    @Test
    @DisplayName("an explicit revision overrides the current one")
    void explicitRevision() {
      score.setCurrentRevision(5);
      when(storageService.getDerived(scoreId, 2, "score.musicxml"))
          .thenReturn(Optional.of("<old/>".getBytes(StandardCharsets.UTF_8)));

      assertThat(controller.musicXml(scoreId, 2).getBody()).isEqualTo("<old/>");
      verify(storageService, never()).getDerived(eq(scoreId), eq(5), any());
    }

    @Test
    @DisplayName("404 when the score has never been processed")
    void musicXmlBeforeProcessing() {
      score.setCurrentRevision(null);

      assertThatThrownBy(() -> controller.musicXml(scoreId, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("404");
    }
  }
}
