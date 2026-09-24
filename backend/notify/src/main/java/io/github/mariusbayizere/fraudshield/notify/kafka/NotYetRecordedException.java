package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * S4: the intent's auto-block event is not in PostgreSQL, and neither is its transaction's kept
 * decision, or the intent does not say which transaction it belongs to.
 *
 * <p>The decision service's spool drains to Kafka and to PostgreSQL independently, so a customer
 * SMS intent can be read before the facts it refers to are written. {@link EnvelopeConsumer}
 * re-reads such a record within its partition's wait budget, and dead-letters it for replay ({@code
 * parent_not_recorded}) once the budget is spent (docs/architecture/decision-fact-ordering.md,
 * section 6).
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
