package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * The intent is not the kept decision's, so its auto-block event will never be written: PostgreSQL
 * holds the transaction's kept decision, and that decision has no such block, or the block belongs
 * to another account. {@link EnvelopeConsumer} dead-letters the record at once, reason {@code
 * not_the_kept_decision}, and logs it at ERROR: whether the customer is told is for a person to
 * decide (docs/architecture/decision-fact-ordering.md, S0 and S3, D-e).
 */
public final class NotTheKeptDecisionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was found, by id only: no protected value
   */
  public NotTheKeptDecisionException(String message) {
    super(message);
  }
}
