package io.github.mariusbayizere.fraudshield.auth.google;

/** Google could not be reached in time (C.4: Google sign-in shows "Temporarily unavailable"). */
public final class GoogleUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the cause
   */
  public GoogleUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
