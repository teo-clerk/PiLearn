package org.pianoml.backend.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.document.InvalidScoreDocumentException;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentService;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.omr.OmrJobStatus;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes the worker → backend persistence loop.
 *
 * <p>Until this existed, {@code ScoreDocumentService.save()} and
 * {@code OmrWorkerClient.fetchDocument()} both worked but nothing joined them, so no
 * document ever reached PostgreSQL. This service is that join: it reacts to a finished
 * OMR job, pulls the canonical document, validates it and persists it.
 *
 * <h2>Why this is idempotent</h2>
 * The worker may deliver a completion more than once — a webhook retry, a poller and a
 * webhook racing, or a redelivery after a backend restart. {@link #ingestCompletedJob}
 * therefore checks whether the job has already been ingested and returns the existing
 * result rather than writing a second revision. Two revisions of identical content would
 * not corrupt anything, but they would silently invalidate learners' in-flight plans,
 * which point at a specific revision.
 *
 * <h2>Why REVIEW_REQUIRED still persists</h2>
 * A partially-recognised score is stored, not discarded. The learner needs to see the
 * result to decide whether to accept, correct or re-run it. What must never happen is
 * the score being presented as complete — that is enforced by
 * {@code ScoreDocumentService.save()}, which rejects any document claiming {@code OK}
 * while carrying dropped pages.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScoreIngestionService {

  private final OmrWorkerClient workerClient;
  private final ScoreDocumentService documentService;
  private final ScoreRepository scoreRepository;

  /** Values written to {@code score.processing_status}. */
  public static final String STATUS_NONE = "NONE";
  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED";
  public static final String STATUS_FAILED = "FAILED";

  /**
   * Ingest a finished OMR job.
   *
   * <p>Safe to call repeatedly for the same job.
   *
   * @param jobId the worker job that completed
   * @return what happened, as a value — callers (webhook handler, poller) must be able
   *     to react without catching exceptions for ordinary outcomes
   */
  @Transactional
  public IngestionOutcome ingestCompletedJob(String jobId) {
    Optional<OmrJobStatus> maybeStatus = workerClient.getJobStatus(jobId);
    if (maybeStatus.isEmpty()) {
      log.warn("ingestion requested for unknown job {}", jobId);
      return IngestionOutcome.failed(jobId, null, "JOB_NOT_FOUND",
          "the worker does not know job " + jobId);
    }

    OmrJobStatus status = maybeStatus.get();

    if (!status.isTerminal()) {
      // Not an error — the poller simply arrived early.
      log.debug("job {} is still {}; nothing to ingest yet", jobId, status.status());
      return IngestionOutcome.pending(jobId, status.scoreId(), status.status());
    }

    UUID scoreId;
    try {
      scoreId = UUID.fromString(status.scoreId());
    } catch (IllegalArgumentException e) {
      log.error("job {} carries an unparseable scoreId '{}'", jobId, status.scoreId());
      return IngestionOutcome.failed(jobId, status.scoreId(), "INVALID_SCORE_ID",
          "scoreId is not a UUID: " + status.scoreId());
    }

    Optional<Score> maybeScore = scoreRepository.findById(scoreId);
    if (maybeScore.isEmpty()) {
      log.error("job {} references score {} which no longer exists", jobId, scoreId);
      return IngestionOutcome.failed(jobId, status.scoreId(), "SCORE_NOT_FOUND",
          "no score " + scoreId);
    }
    Score score = maybeScore.get();

    if ("FAILED".equals(status.status())) {
      return recordFailure(score, jobId, status);
    }

    // Idempotency: a redelivery of a job we already ingested must not create a second
    // revision, because learners' plans point at a specific revision.
    if (jobId.equals(score.getOmrJobId()) && score.getCurrentRevision() != null) {
      log.info("job {} already ingested as revision {}; skipping",
          jobId, score.getCurrentRevision());
      return documentService.find(scoreId, score.getCurrentRevision())
          .map(existing -> IngestionOutcome.alreadyIngested(jobId, status.scoreId(), existing))
          .orElseGet(() -> IngestionOutcome.failed(jobId, status.scoreId(),
              "REVISION_MISSING",
              "score claims revision " + score.getCurrentRevision() + " but it is absent"));
    }

    Optional<String> maybeDocument = workerClient.fetchDocument(status.scoreId(), null);
    if (maybeDocument.isEmpty()) {
      log.error("job {} finished as {} but the worker served no document",
          jobId, status.status());
      return recordFailure(score, jobId, "DOCUMENT_UNAVAILABLE",
          "the worker reported " + status.status() + " but served no ScoreDocument");
    }

    ScoreDocumentEntity saved;
    try {
      saved = documentService.save(scoreId, maybeDocument.get(), null);
    } catch (InvalidScoreDocumentException e) {
      // The document contradicted itself — e.g. claimed OK while dropping pages. Better
      // to record a failure than to persist something that will mislead a learner.
      log.error("job {} produced an invalid ScoreDocument: {}", jobId, e.getMessage());
      return recordFailure(score, jobId, "INVALID_DOCUMENT", e.getMessage());
    }

    boolean needsReview = saved.requiresReview() || status.requiresReview();

    score.setCurrentRevision(saved.getRevision());
    score.setDocumentConfidence(saved.getDocumentConfidence());
    score.setOmrJobId(jobId);
    score.setProcessingStatus(needsReview ? STATUS_REVIEW_REQUIRED : STATUS_COMPLETED);
    score.setHasFiles(true);
    score.setUploadedAt(OffsetDateTime.now());
    if (saved.getMeasureCount() != null) {
      score.setMeasuresCount(saved.getMeasureCount());
    }
    if (saved.getGlobalGrade() != null) {
      score.setGrade(saved.getGlobalGrade().floatValue());
    }
    scoreRepository.save(score);

    if (needsReview) {
      log.warn(
          "ingested score {} revision {} as REVIEW_REQUIRED ({}/{} pages recognised); "
              + "a roadmap built on this may teach the wrong bars",
          scoreId, saved.getRevision(), saved.getRecognisedPages(), saved.getSourcePages());
    } else {
      log.info("ingested score {} revision {} ({} measures, grade {})",
          scoreId, saved.getRevision(), saved.getMeasureCount(), saved.getGlobalGrade());
    }

    return IngestionOutcome.ingested(jobId, status.scoreId(), saved, needsReview);
  }

  /** Mark a score as queued when its OMR job is accepted. */
  @Transactional
  public void markQueued(UUID scoreId, String jobId) {
    scoreRepository.findById(scoreId).ifPresent(score -> {
      score.setOmrJobId(jobId);
      score.setProcessingStatus(STATUS_QUEUED);
      scoreRepository.save(score);
    });
  }

  /** Reflect in-flight progress so the library can show it without calling the worker. */
  @Transactional
  public void markRunning(UUID scoreId) {
    scoreRepository.findById(scoreId).ifPresent(score -> {
      score.setProcessingStatus(STATUS_RUNNING);
      scoreRepository.save(score);
    });
  }

  private IngestionOutcome recordFailure(Score score, String jobId, OmrJobStatus status) {
    return recordFailure(score, jobId,
        status.errorCode() == null ? "PIPELINE_FAILED" : status.errorCode(),
        status.errorDetail() == null ? "the OMR pipeline failed" : status.errorDetail());
  }

  private IngestionOutcome recordFailure(
      Score score, String jobId, String code, String detail) {
    score.setProcessingStatus(STATUS_FAILED);
    score.setOmrJobId(jobId);
    scoreRepository.save(score);
    log.error("ingestion failed for score {} (job {}): {} — {}",
        score.getId(), jobId, code, detail);
    return IngestionOutcome.failed(jobId, score.getId().toString(), code, detail);
  }
}
