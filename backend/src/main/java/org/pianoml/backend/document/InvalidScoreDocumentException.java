package org.pianoml.backend.document;

/** A ScoreDocument that is malformed or internally contradictory. */
public class InvalidScoreDocumentException extends RuntimeException {
  public InvalidScoreDocumentException(String message) {
    super(message);
  }
}
