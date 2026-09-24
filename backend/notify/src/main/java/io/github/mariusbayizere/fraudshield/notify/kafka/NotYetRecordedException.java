package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * A fact the record depends on has not reached PostgreSQL yet.
 *
 * <p>The decision service's spool drains to Kafka and to PostgreSQL independently, so a customer
 * SMS intent can be read before the auto-block event it refers to is written. {@link
 * EnvelopeConsumer} re-reads such a record until PostgreSQL has answered without the parent for
 * {@link EnvelopeConsumer#PARENT_WAIT}, and then dead-letters it (reason {@code
 * parent_not_recorded}), so a partition never stalls for ever behind a parent that will not arrive
 * (ADR 0057).
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
