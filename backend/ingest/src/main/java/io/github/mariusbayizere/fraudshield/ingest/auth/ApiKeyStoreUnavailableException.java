package io.github.mariusbayizere.fraudshield.ingest.auth;

/**
 * The API-key module cannot tell whether a key is good because its store is unavailable: the
 * request is answered 503 with Retry-After, never 401 (docs/architecture/api-key-cache-contract.md
 * §3). Any other exception from {@link ApiKeyAuthenticator} is an error, answered 500.
 */
public final class ApiKeyStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param cause the module's own signal
   */
  public ApiKeyStoreUnavailableException(Throwable cause) {
    super("the API-key store is unavailable", cause);
  }
}
