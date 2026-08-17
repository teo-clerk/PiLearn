package org.pianoml.backend.omr;

/** Raised when the worker responds with a non-2xx status. */
public class OmrWorkerException extends RuntimeException {

  private final int statusCode;

  public OmrWorkerException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }

  /** 5xx and 429 are worth retrying; a 4xx rejection will fail identically next time. */
  public boolean isRetryable() {
    return statusCode >= 500 || statusCode == 429;
  }
}
