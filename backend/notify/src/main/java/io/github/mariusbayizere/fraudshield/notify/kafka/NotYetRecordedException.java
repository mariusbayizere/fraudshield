package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * A fact the record depends on has not reached PostgreSQL yet.
 *
 * <p>The decision service's spool drains to Kafka and to PostgreSQL independently, so a customer
 * SMS intent can be read before the auto-block event it refers to is written. {@link
 * EnvelopeConsumer} re-reads such a record until the parent is there, and never dead-letters it for
 * that reason; a long wait is logged at WARN (ADR 0057).
 */
public final class NotYetRecordedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is missing, by id only: no protected value
   */
  public NotYetRecordedException(String message) {
    super(message);
  }
}
