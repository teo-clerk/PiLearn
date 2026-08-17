package org.pianoml.backend.ingestion;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Receives job-completion callbacks from the OMR worker.
 *
 * <p>Returns 200 for every outcome the worker should not retry — including ingestion
 * failures. A worker retry cannot fix an invalid document or a deleted score, and a 5xx
 * would put the callback into a redelivery loop that never converges. Only genuine
 * server-side faults return 5xx.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
public class IngestionCallbackController {

  private final ScoreIngestionService ingestionService;

  @Value("${omr.worker.token:}")
  private String expectedToken;

  public record CallbackRequest(@NotBlank String jobId, String scoreId, String status) {}

  @PostMapping("/callback")
  public ResponseEntity<IngestionOutcome> onJobComplete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody CallbackRequest request) {

    if (!isAuthorised(authorization)) {
      log.warn("rejected an ingestion callback with a bad or missing token");
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    if (request.jobId() == null || request.jobId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }

    IngestionOutcome outcome = ingestionService.ingestCompletedJob(request.jobId());

    // 200 even on failure: the worker retrying will not make an invalid document valid.
    return ResponseEntity.ok(outcome);
  }

  /**
   * Poll-driven ingestion, for environments where the worker cannot reach the backend.
   * Same idempotent path as the callback.
   */
  @PostMapping("/jobs/{jobId}/sync")
  public ResponseEntity<IngestionOutcome> sync(@PathVariable String jobId) {
    return ResponseEntity.ok(ingestionService.ingestCompletedJob(jobId));
  }

  private boolean isAuthorised(String authorization) {
    if (expectedToken == null || expectedToken.isBlank()) {
      // No token configured: local development. Logged so it cannot pass unnoticed.
      log.debug("omr.worker.token is unset — accepting the callback unauthenticated");
      return true;
    }
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return false;
    }
    // Constant-time compare: a token check that leaks timing is a token check that leaks.
    return MessageDigest.isEqual(
        authorization.substring(7).getBytes(StandardCharsets.UTF_8),
        expectedToken.getBytes(StandardCharsets.UTF_8));
  }
}
