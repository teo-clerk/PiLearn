package org.pianoml.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.model.ErrorApiInfoError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<org.pianoml.backend.model.ErrorApiInfo> handleBadCredentialsException(Exception ex) {
    log.warn("{}", ex.getClass().getName());
    org.pianoml.backend.model.ErrorApiInfo errorApiInfo = makeFromException(ex);
    return new ResponseEntity<>(errorApiInfo, HttpStatus.FORBIDDEN);
  }

  /**
   * Anything unexpected.
   *
   * <p>The client is told that something failed and nothing else. This handler used to
   * serialise the exception class, its message and the full stack trace into the
   * response — which meant an ordinary constraint violation returned the failing SQL
   * statement, every column name in the table, and the internal package layout to
   * whoever made the request. The detail belongs in the log, where it already is.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<org.pianoml.backend.model.ErrorApiInfo> handleAll(Exception ex) {
    log.error("An unexpected error occurred", ex);
    return new ResponseEntity<>(
        opaqueError("Something went wrong. Please try again."),
        HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @ExceptionHandler(MusicBrainzException.class)
  public ResponseEntity<org.pianoml.backend.model.ErrorApiInfo> handleBrainz(Exception ex) {
    log.error("MusicBrainz lookup failed", ex);
    return new ResponseEntity<>(
        opaqueError("No matching release could be looked up."), HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(EntityAlreadyExistsException.class)
  public ResponseEntity<org.pianoml.backend.model.ErrorApiInfo> handleEntityExistsException(Exception ex) {
    log.warn("{}", ex.getClass().getName());
    org.pianoml.backend.model.ErrorApiInfo errorApiInfo = makeFromException(ex);
    return new ResponseEntity<>(errorApiInfo, HttpStatus.CONFLICT);
  }

  /** An error body carrying a safe message and nothing internal. */
  private org.pianoml.backend.model.ErrorApiInfo opaqueError(String message) {
    org.pianoml.backend.model.ErrorApiInfo errorApiInfo =
        new org.pianoml.backend.model.ErrorApiInfo();
    ErrorApiInfoError error = new ErrorApiInfoError();
    error.setMessage(message);
    errorApiInfo.setError(error);
    return errorApiInfo;
  }

  /**
   * Used only where the exception is one we raised deliberately and whose message is
   * written for the client (a duplicate account, a bad password). No stack trace is
   * attached — it must never reach a response.
   */
  private org.pianoml.backend.model.ErrorApiInfo makeFromException(Exception ex) {
    org.pianoml.backend.model.ErrorApiInfo errorApiInfo = new org.pianoml.backend.model.ErrorApiInfo();
    ErrorApiInfoError errorApiInfoError = new ErrorApiInfoError();
    errorApiInfoError.setMessage(ex.getMessage());
    errorApiInfoError.setName(ex.getClass().getName());
    errorApiInfo.setError(errorApiInfoError);
    return errorApiInfo;
  }
}
