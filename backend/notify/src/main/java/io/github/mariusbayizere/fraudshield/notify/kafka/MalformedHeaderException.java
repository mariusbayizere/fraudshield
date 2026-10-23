package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * S3m: an SMS intent's {@code fs-transaction-id} is malformed and its auto-block event is not
 * visible, so which decision PostgreSQL kept cannot be read from the record (docs/architecture/
 * decision-fact-ordering.md, 5.3). Nothing was sent, issued or recorded; the record is
 * dead-lettered at once as {@code malformed_header}, and a replay sends it once B exists.
 */
public final class MalformedHeaderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which block, without any protected value
   */
  public MalformedHeaderException(String message) {
    super(message);
  }
}
