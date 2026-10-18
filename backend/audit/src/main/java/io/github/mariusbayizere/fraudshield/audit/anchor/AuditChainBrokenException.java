package io.github.mariusbayizere.fraudshield.audit.anchor;

/** The audit chain does not link up, so the anchoring job refuses to sign it (D-32). */
public final class AuditChainBrokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message position and nature of the break
   */
  public AuditChainBrokenException(String message) {
    super(message);
  }
}
