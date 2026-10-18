package io.github.mariusbayizere.fraudshield.decision.application.port;

/** The scorer did not answer within the deadline, failed, or its circuit is open (C.4). */
public final class ScorerUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   * @param cause the underlying failure, or null
   */
  public ScorerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
