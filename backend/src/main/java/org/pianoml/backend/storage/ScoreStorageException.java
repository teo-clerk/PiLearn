package org.pianoml.backend.storage;

/** Object storage could not satisfy a read or write. */
public class ScoreStorageException extends RuntimeException {

  public ScoreStorageException(String message) {
    super(message);
  }

  public ScoreStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
