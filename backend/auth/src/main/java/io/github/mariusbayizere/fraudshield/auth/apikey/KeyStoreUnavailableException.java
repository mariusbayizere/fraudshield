package io.github.mariusbayizere.fraudshield.auth.apikey;

/**
 * The key store cannot answer and the cache may not stand in for it, so the key is neither known
 * good nor known bad: the filters answer 503 with Retry-After and do not count a failed key
 * (docs/architecture/api-key-cache-contract.md R3, R8, R9). It is the only signal for a 503; any
 * other error from the key store propagates unchanged and is answered 500 (R7).
 */
public final class KeyStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be done
   * @param cause the unavailability, or null when the bulkhead refused before any read
   */
  public KeyStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
