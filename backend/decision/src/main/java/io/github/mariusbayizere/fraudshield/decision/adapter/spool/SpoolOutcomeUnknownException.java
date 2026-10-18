package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

/**
 * The writer had taken the record but its fsync did not finish in time: the record may or may not
 * become durable. Unlike {@link SpoolFullException}, the caller cannot treat it as not recorded.
 */
public final class SpoolOutcomeUnknownException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   * @param cause the underlying failure
   */
  public SpoolOutcomeUnknownException(String message, Throwable cause) {
    super(message, cause);
  }
}
