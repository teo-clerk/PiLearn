package org.pianoml.backend.ingestion;

import org.pianoml.backend.document.ScoreDocumentEntity;

/**
 * Result of an ingestion attempt.
 *
 * <p>A value, not an exception: a webhook handler and a poller both need to react to
 * "still running" and "already done" without treating them as errors. The legacy path
 * buried failures in a {@code CompletableFuture} callback where no caller could see them
 * (AUDIT §R2); this makes every outcome explicit and unmissable.
 */
public record IngestionOutcome(
    Kind kind,
    String jobId,
    String scoreId,
    Integer revision,
    boolean requiresReview,
    String errorCode,
    String errorDetail) {

  public enum Kind {
    /** The document was fetched, validated and persisted. */
    INGESTED,
    /** A redelivery of a job already ingested; nothing was written. */
    ALREADY_INGESTED,
    /** The job has not finished yet. Not an error. */
    PENDING,
    /** The job failed, or its output could not be persisted. */
    FAILED
  }

  public static IngestionOutcome ingested(
      String jobId, String scoreId, ScoreDocumentEntity entity, boolean requiresReview) {
    return new IngestionOutcome(
        Kind.INGESTED, jobId, scoreId, entity.getRevision(), requiresReview, null, null);
  }

  public static IngestionOutcome alreadyIngested(
      String jobId, String scoreId, ScoreDocumentEntity entity) {
    return new IngestionOutcome(Kind.ALREADY_INGESTED, jobId, scoreId,
        entity.getRevision(), entity.requiresReview(), null, null);
  }

  public static IngestionOutcome pending(String jobId, String scoreId, String status) {
    return new IngestionOutcome(Kind.PENDING, jobId, scoreId, null, false, status, null);
  }

  public static IngestionOutcome failed(
      String jobId, String scoreId, String errorCode, String errorDetail) {
    return new IngestionOutcome(
        Kind.FAILED, jobId, scoreId, null, false, errorCode, errorDetail);
  }

  /** True when a document is now available, whether written just now or previously. */
  public boolean hasDocument() {
    return kind == Kind.INGESTED || kind == Kind.ALREADY_INGESTED;
  }

  public boolean isFailure() {
    return kind == Kind.FAILED;
  }
}
