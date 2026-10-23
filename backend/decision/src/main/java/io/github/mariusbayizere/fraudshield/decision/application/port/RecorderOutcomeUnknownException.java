package io.github.mariusbayizere.fraudshield.decision.application.port;

/**
 * The events may or may not have been recorded: the spool took them but did not confirm the fsync
 * in time. A caller must not treat the decision as never made (a retry could decide it twice), nor
 * as made (nothing confirms it); the ingest API marks the submission uncertain (ADR 0067).
 */
public final class RecorderOutcomeUnknownException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   * @param cause the underlying failure
   */
  public RecorderOutcomeUnknownException(String message, Throwable cause) {
    super(message, cause);
  }
}
