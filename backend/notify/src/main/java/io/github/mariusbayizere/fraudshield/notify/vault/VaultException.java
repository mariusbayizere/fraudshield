package io.github.mariusbayizere.fraudshield.notify.vault;

/**
 * The vault could not answer. Its message never carries a plaintext value, a key or a ciphertext:
 * the vault's failures are logged like any other, and a log line is not a place for PII (D-20).
 */
public final class VaultException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why, without any protected value
   * @param cause the underlying failure
   */
  public VaultException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates the exception.
   *
   * @param message why, without any protected value
   */
  public VaultException(String message) {
    super(message);
  }
}
