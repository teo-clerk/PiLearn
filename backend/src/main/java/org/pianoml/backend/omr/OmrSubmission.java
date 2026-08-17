package org.pianoml.backend.omr;

import java.util.Optional;

/**
 * Result of a submission attempt.
 *
 * <p>Deliberately a value, not an exception: the legacy path buried failures in a
 * {@code CompletableFuture} callback where the caller could not see them (AUDIT §R2).
 * A caller here cannot ignore the outcome without noticing.
 */
public record OmrSubmission(
    boolean accepted,
    String jobId,
    String scoreId,
    boolean deduplicated,
    String errorCode,
    String errorDetail) {

  public static OmrSubmission accepted(OmrSubmitResponse response) {
    return new OmrSubmission(
        true, response.jobId(), response.scoreId(), response.deduplicated(), null, null);
  }

  public static OmrSubmission failure(String scoreId, String errorCode, String detail) {
    return new OmrSubmission(false, null, scoreId, false, errorCode, detail);
  }

  public Optional<String> jobIdIfAccepted() {
    return accepted ? Optional.ofNullable(jobId) : Optional.empty();
  }

  /** The worker being down is transient; a rejected file is not. */
  public boolean isRetryable() {
    return !accepted && "WORKER_UNREACHABLE".equals(errorCode);
  }
}
