package org.pianoml.backend.omr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Worker job state.
 *
 * <p>{@code pages} is the reconciliation that did not exist in the legacy pipeline: it
 * reports how many pages the source had against how many were actually recognised. A
 * non-empty {@code droppedPages} means measures are missing from the score, which is why
 * {@link #requiresReview()} exists as a first-class question rather than a log line.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OmrJobStatus(
    String jobId,
    String scoreId,
    String status,
    String stage,
    double progress,
    String message,
    int attempt,
    String createdAt,
    String updatedAt,
    PageReconciliation pages,
    String errorCode,
    String errorDetail,
    Integer documentRevision) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PageReconciliation(
      Integer sourcePages,
      Integer recognisedPages,
      List<Integer> droppedPages,
      Double coverage) {

    public boolean hasDroppedPages() {
      return droppedPages != null && !droppedPages.isEmpty();
    }
  }

  public boolean isTerminal() {
    return "COMPLETED".equals(status) || "FAILED".equals(status)
        || "REVIEW_REQUIRED".equals(status);
  }

  public boolean isSuccessful() {
    return "COMPLETED".equals(status);
  }

  /**
   * True when a human must look before this score is used for practice. A roadmap built
   * on a score that is missing pages teaches the wrong bars.
   */
  public boolean requiresReview() {
    return "REVIEW_REQUIRED".equals(status)
        || (pages != null && pages.hasDroppedPages());
  }
}
