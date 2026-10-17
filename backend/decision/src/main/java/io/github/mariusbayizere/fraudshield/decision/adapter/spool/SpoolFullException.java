package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

/** The spool reached its size bound, or is closed; the record was not accepted. */
public final class SpoolFullException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   */
  public SpoolFullException(String message) {
    super(message);
  }
}
