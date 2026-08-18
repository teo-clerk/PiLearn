package org.pianoml.backend.ingestion;

import java.util.List;

/**
 * Ingestion state for one score.
 *
 * <p>The status vocabulary here is the CLIENT's, not the worker's: the frontend polls
 * for QUEUED / PROCESSING / READY / REVIEW_REQUIRED / FAILED and should not have to know
 * that the pipeline internally distinguishes RASTERISE from MERGE. {@code stage} carries
 * the finer detail for anyone who wants it.
 */
public record ScoreStatusResponse(
    String scoreId,
    String status,
    String stage,
    double progress,
    String message,
    Integer revision,
    Double confidence,
    Integer sourcePages,
    Integer recognisedPages,
    List<Integer> droppedPages,
    int warningCount,
    String errorCode,
    String errorDetail) {

  public static final String QUEUED = "QUEUED";
  public static final String PROCESSING = "PROCESSING";
  public static final String READY = "READY";
  public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
  public static final String FAILED = "FAILED";

  /** True once the client should stop polling. */
  public boolean isTerminal() {
    return READY.equals(status) || REVIEW_REQUIRED.equals(status) || FAILED.equals(status);
  }
}
