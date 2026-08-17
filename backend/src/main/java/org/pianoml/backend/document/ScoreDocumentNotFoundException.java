package org.pianoml.backend.document;

import java.util.UUID;

public class ScoreDocumentNotFoundException extends RuntimeException {
  public ScoreDocumentNotFoundException(UUID scoreId, Integer revision) {
    super("no ScoreDocument for score " + scoreId
        + (revision == null ? " (latest)" : " revision " + revision));
  }
}
