package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * The envelope parsed, but its payload lacks a field the handler needs, or holds one it cannot
 * read. No retry can change that, so {@link EnvelopeConsumer} dead-letters the record at once as
 * {@code malformed_envelope}, rather than re-reading it for ever (the implementation review of
 * bd48557).
 */
public final class MalformedPayloadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong, without any protected value
   * @param cause the parsing failure
   */
  public MalformedPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
