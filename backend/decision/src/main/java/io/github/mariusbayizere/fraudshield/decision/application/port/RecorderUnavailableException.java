package io.github.mariusbayizere.fraudshield.decision.application.port;

/** The durable spool refused the events (full, or the disk failed); nothing was recorded. */
public final class RecorderUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   * @param cause the underlying failure, or null
   */
  public RecorderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
