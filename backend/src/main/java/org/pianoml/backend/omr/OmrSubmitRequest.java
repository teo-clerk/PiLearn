package org.pianoml.backend.omr;

/** What the backend asks the OMR worker to ingest. */
public record OmrSubmitRequest(
    String scoreId,
    String storageKey,
    String title,
    String composer,
    boolean makeFingering) {

  public OmrSubmitRequest {
    if (scoreId == null || scoreId.isBlank()) {
      throw new IllegalArgumentException("scoreId is required");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title is required");
    }
    composer = (composer == null || composer.isBlank()) ? "Unknown" : composer;
  }
}
