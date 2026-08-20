package org.pianoml.backend.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentService;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.omr.OmrJobStatus;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;
import org.pianoml.backend.storage.ScoreStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of ingestion: status polling, the canonical document, and the engraving
 * source.
 *
 * <p>These three endpoints are what the practice surface and the upload screen actually
 * call. They deliberately front the worker rather than exposing it: the client polls
 * {@code /scores/{id}/status}, not the worker's own job endpoint, so the worker stays
 * private and the status vocabulary stays stable even if the pipeline's internal stages
 * change.
 */
@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
@Slf4j
public class ScoreStatusController {

  private final ScoreRepository scoreRepository;
  private final ScoreDocumentService documentService;
  private final ScoreStorageService storageService;
  private final OmrWorkerClient workerClient;
  private final ScoreIngestionService ingestionService;

  /**
   * Current ingestion state.
   *
   * <p>Answered from the database first. The worker is consulted only while a job is
   * genuinely in flight — polling it for a score that finished hours ago would put load
   * on the pipeline for no reason.
   */
  @GetMapping("/{scoreId}/status")
  public ResponseEntity<ScoreStatusResponse> status(@PathVariable UUID scoreId) {
    Score score = findScore(scoreId);
    String stored = score.getProcessingStatus() == null
        ? ScoreIngestionService.STATUS_NONE
        : score.getProcessingStatus();

    Optional<ScoreDocumentEntity> document = documentService.findLatest(scoreId);

    // Terminal states are answered entirely from what we already know.
    if (ScoreIngestionService.STATUS_COMPLETED.equals(stored)
        || ScoreIngestionService.STATUS_REVIEW_REQUIRED.equals(stored)) {
      return ResponseEntity.ok(terminal(score, document));
    }
    if (ScoreIngestionService.STATUS_FAILED.equals(stored)) {
      return ResponseEntity.ok(
          new ScoreStatusResponse(
              scoreId.toString(), ScoreStatusResponse.FAILED, "FAILED", 0,
              "Processing failed.", null, null, null, null, List.of(), 0,
              "PIPELINE_FAILED", "The score could not be processed."));
    }

    // In flight: ask the worker for live progress, but never fail the request because
    // the worker is briefly unreachable — the client would then abandon a job that is
    // still running perfectly well.
    String jobId = score.getOmrJobId();
    if (jobId != null) {
      Optional<OmrJobStatus> live = workerClient.getJobStatus(jobId);
      if (live.isPresent()) {
        OmrJobStatus job = live.get();

        // The worker has finished, but its output only reaches our database when
        // something pulls it across. In deployments where the worker cannot call back,
        // nothing did — so polling reached READY, the client navigated to the practice
        // surface, and every artefact 404'd. Pulling here closes the loop using the
        // same idempotent path as the callback.
        if (job.isTerminal() && !"FAILED".equals(job.status())
            && score.getCurrentRevision() == null) {
          // Pass the score explicitly: on the dedup path the worker echoes back the
          // first submitter's id, and ingesting into that would leave this uploader
          // with a score that reports READY and serves nothing.
          IngestionOutcome outcome = ingestionService.ingestCompletedJob(jobId, scoreId);
          if (outcome.kind() == IngestionOutcome.Kind.FAILED) {
            log.warn(
                "could not pull the finished document for score {} (job {}): {} — {}",
                scoreId, jobId, outcome.errorCode(), outcome.errorDetail());
          }
          // Re-read: the pull just wrote the revision the client needs.
          score = findScore(scoreId);
          document = documentService.findLatest(scoreId);
          if (score.getCurrentRevision() != null) {
            return ResponseEntity.ok(terminal(score, document));
          }
        }

        return ResponseEntity.ok(fromWorker(scoreId, job));
      }
      log.debug("worker did not answer for job {}; reporting stored status {}", jobId, stored);
    }

    String clientStatus = ScoreIngestionService.STATUS_RUNNING.equals(stored)
        ? ScoreStatusResponse.PROCESSING
        : ScoreStatusResponse.QUEUED;

    return ResponseEntity.ok(
        new ScoreStatusResponse(
            scoreId.toString(), clientStatus, stored, 0,
            "Waiting for the transcription service…", null, null, null, null,
            List.of(), 0, null, null));
  }

  private ScoreStatusResponse terminal(Score score, Optional<ScoreDocumentEntity> document) {
    ScoreDocumentEntity entity = document.orElse(null);
    boolean needsReview =
        ScoreIngestionService.STATUS_REVIEW_REQUIRED.equals(score.getProcessingStatus())
            || (entity != null && entity.requiresReview());

    List<Integer> dropped = List.of();
    int warnings = 0;
    if (entity != null
        && entity.getSourcePages() != null
        && entity.getRecognisedPages() != null) {
      warnings = Math.max(0, entity.getSourcePages() - entity.getRecognisedPages());
    }

    return new ScoreStatusResponse(
        score.getId().toString(),
        needsReview ? ScoreStatusResponse.REVIEW_REQUIRED : ScoreStatusResponse.READY,
        "DONE",
        1.0,
        needsReview
            ? "Processed, but some pages could not be read."
            : "Ready to practise.",
        score.getCurrentRevision(),
        score.getDocumentConfidence(),
        entity == null ? null : entity.getSourcePages(),
        entity == null ? null : entity.getRecognisedPages(),
        dropped,
        warnings,
        null,
        null);
  }

  private ScoreStatusResponse fromWorker(UUID scoreId, OmrJobStatus job) {
    List<Integer> dropped =
        job.pages() == null || job.pages().droppedPages() == null
            ? List.of()
            : job.pages().droppedPages();

    String status;
    if ("FAILED".equals(job.status())) {
      status = ScoreStatusResponse.FAILED;
    } else if (job.requiresReview()) {
      status = ScoreStatusResponse.REVIEW_REQUIRED;
    } else if (job.isSuccessful()) {
      status = ScoreStatusResponse.READY;
    } else if ("RUNNING".equals(job.status())) {
      status = ScoreStatusResponse.PROCESSING;
    } else {
      status = ScoreStatusResponse.QUEUED;
    }

    return new ScoreStatusResponse(
        scoreId.toString(),
        status,
        job.stage(),
        job.progress(),
        job.message(),
        job.documentRevision(),
        null,
        job.pages() == null ? null : job.pages().sourcePages(),
        job.pages() == null ? null : job.pages().recognisedPages(),
        dropped,
        dropped.size(),
        job.errorCode(),
        job.errorDetail());
  }

  /**
   * The canonical ScoreDocument.
   *
   * <p>Served as a raw JSON string rather than re-serialised through a Java type: the
   * worker owns the schema, and round-tripping it here would risk silently dropping
   * fields the backend does not model.
   */
  @GetMapping(value = "/{scoreId}/document", produces = MediaType.APPLICATION_JSON_VALUE)
  @Transactional(readOnly = true)
  public ResponseEntity<String> document(
      @PathVariable UUID scoreId,
      @RequestParam(required = false) Integer revision) {

    String json = documentService
        .findDocumentJson(scoreId, revision)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No processed document for this score yet."));

    // Immutable per (scoreId, revision), so it can be cached hard.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        .body(json);
  }

  /** The compact alignment index — the hot path for cursor tracking. */
  @GetMapping(value = "/{scoreId}/document/index", produces = MediaType.APPLICATION_JSON_VALUE)
  @Transactional(readOnly = true)
  public ResponseEntity<String> alignmentIndex(
      @PathVariable UUID scoreId,
      @RequestParam(required = false) Integer revision) {

    return documentService
        .findAlignmentIndexJson(scoreId, revision)
        .map(json -> ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(json))
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No alignment index for this score yet."));
  }

  /**
   * The engraving source, for OSMD.
   *
   * <p>Streams from object storage rather than the database: the document carries
   * structure and alignment, but MusicXML is a separate derived artefact and embedding
   * it in the JSON would double the payload for every consumer that only wants the
   * alignment index.
   */
  @GetMapping(value = "/{scoreId}/musicxml", produces = MediaType.APPLICATION_XML_VALUE)
  @Transactional(readOnly = true)
  public ResponseEntity<String> musicXml(
      @PathVariable UUID scoreId,
      @RequestParam(required = false) Integer revision) {

    Score score = findScore(scoreId);
    Integer resolved = revision != null ? revision : score.getCurrentRevision();

    if (resolved == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "This score has not been processed yet.");
    }

    byte[] content = storageService
        .getDerived(scoreId, resolved, "score.musicxml")
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No engraving source for this score revision."));

    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        .body(new String(content, StandardCharsets.UTF_8));
  }

  private Score findScore(UUID scoreId) {
    return scoreRepository
        .findById(scoreId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No score with id " + scoreId));
  }
}
