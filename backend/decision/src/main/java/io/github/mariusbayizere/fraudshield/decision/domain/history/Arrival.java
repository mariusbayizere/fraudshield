package io.github.mariusbayizere.fraudshield.decision.domain.history;

import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One earlier transaction of an account, as the online feature store keeps it: token identifiers,
 * the RWF amount converted at its own date, and its location. No personal data.
 *
 * @param transactionId the transaction
 * @param at its timestamp
 * @param amountRwf its RWF amount
 * @param counterparty counterparty token
 * @param latitude latitude
 * @param longitude longitude
 * @param counterpartyCountry counterparty country, or null
 * @param device device token, or null without a fingerprint
 */
public record Arrival(
    UUID transactionId,
    Instant at,
    BigDecimal amountRwf,
    String counterparty,
    double latitude,
    double longitude,
    String counterpartyCountry,
    String device) {

  /** Requires the mandatory fields. */
  public Arrival {
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(amountRwf, "amountRwf");
    Objects.requireNonNull(counterparty, "counterparty");
  }

  /**
   * The arrival a decided transaction becomes.
   *
   * @param t the transaction
   * @return its arrival
   */
  public static Arrival of(Transaction t) {
    return new Arrival(
        t.transactionId(),
        t.transactionTimestamp(),
        t.amountRwf(),
        t.counterpartyToken(),
        t.latitude(),
        t.longitude(),
        t.counterpartyCountry(),
        t.deviceToken());
  }
}
