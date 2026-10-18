package io.github.mariusbayizere.fraudshield.notify.sms;

import java.io.IOException;

/**
 * The SMS provider may have accepted the message, but no readable acceptance came back: a 2xx reply
 * that cannot be read, or a request that timed out after it was sent (docs/architecture/
 * decision-fact-ordering.md, 12.4). Recorded FAILED, never retried, and counted as possibly
 * delivered.
 */
public final class ProviderReplyUnknownException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was not known, without the recipient or the text
   * @param cause the underlying failure
   */
  public ProviderReplyUnknownException(String message, Throwable cause) {
    super(message, cause);
  }
}
