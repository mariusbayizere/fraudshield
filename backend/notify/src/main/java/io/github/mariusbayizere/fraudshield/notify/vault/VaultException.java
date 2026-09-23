package io.github.mariusbayizere.fraudshield.notify.vault;

/**
 * The vault could not answer. Its message never carries a plaintext value, a key or a ciphertext:
 * the vault's failures are logged like any other, and a log line is not a place for PII (D-20).
 */
public final class VaultException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Whether no retry can succeed: a row that does not verify, a key this deployment lacks. */
  private final boolean permanent;

  /**
   * Creates the exception.
   *
   * @param message why, without any protected value
   * @param cause the underlying failure
   */
  public VaultException(String message, Throwable cause) {
    this(message, cause, false);
  }

  private VaultException(String message, Throwable cause, boolean permanent) {
    super(message, cause);
    this.permanent = permanent;
  }

  /**
   * Creates the exception.
   *
   * @param message why, without any protected value
   */
  public VaultException(String message) {
    this(message, null, false);
  }

  /**
   * A failure no retry can fix: the stored row does not verify, or it names a key this deployment
   * does not hold. A consumer dead-letters the work rather than retrying it for ever.
   *
   * @param message why, without any protected value
   * @param cause the underlying failure, or null
   * @return the exception
   */
  public static VaultException permanent(String message, Throwable cause) {
    return new VaultException(message, cause, true);
  }

  /**
   * Whether no retry can succeed; a failure that is not permanent may pass (a vault or key service
   * that is down).
   *
   * @return true for a permanent failure
   */
  public boolean permanent() {
    return permanent;
  }
}
