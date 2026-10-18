package io.github.mariusbayizere.fraudshield.notify.vault;

/**
 * The key service is unavailable: unreachable, timed out, throttled, or failing on its side (docs/
 * architecture/decision-fact-ordering.md, 12.2). An outage, never the record's own failure, so a
 * consumer retries it without bound and never dead-letters a record for it. Not permanent.
 */
public final class KmsUnavailableException extends VaultException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why, without any protected value
   * @param cause the underlying failure, or null
   */
  public KmsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
