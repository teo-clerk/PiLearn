package org.pianoml.backend.ingestion;

/**
 * The 202 payload returned when an upload is accepted.
 *
 * <p>{@code scoreId} is what the client polls and ultimately navigates to;
 * {@code jobId} is included for support and log correlation.
 */
public record UploadAcceptedResponse(String scoreId, String jobId, String status) {

  public static UploadAcceptedResponse queued(String scoreId, String jobId) {
    return new UploadAcceptedResponse(scoreId, jobId, ScoreIngestionService.STATUS_QUEUED);
  }
}
