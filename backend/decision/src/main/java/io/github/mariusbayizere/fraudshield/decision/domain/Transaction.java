package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A validated, accepted transaction. Identifiers are opaque tokens; nothing here is personal data
 * (NFR-SEC-03).
 *
 * @param institutionId owning institution, from the authenticated API key
 * @param transactionId idempotency key (FR-01-03)
 * @param accountToken sending account
 * @param counterpartyToken receiving account
 * @param amount amount in the transaction currency
 * @param amountRwf amount normalised to RWF at the transaction date (E.2)
 * @param channel payment channel
 * @param merchantCategoryCode four-digit MCC
 * @param latitude latitude in degrees
 * @param longitude longitude in degrees
 * @param deviceToken device fingerprint token; null for USSD and unknown devices (D-04)
 * @param agentToken agent token; present for AGENT_BANKING
 * @param counterpartyCountry ISO 3166-1 alpha-2 of the counterparty, when known
 * @param transactionTimestamp when the payment happened, UTC
 * @param receivedAt when FraudShield accepted it
 */
public record Transaction(
    UUID institutionId,
    UUID transactionId,
    String accountToken,
    String counterpartyToken,
    Money amount,
    BigDecimal amountRwf,
    Channel channel,
    String merchantCategoryCode,
    double latitude,
    double longitude,
    String deviceToken,
    String agentToken,
    String counterpartyCountry,
    Instant transactionTimestamp,
    Instant receivedAt) {

  /** Requires the mandatory fields and the AGENT_BANKING agent (ADR 0011 section 12). */
  public Transaction {
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(accountToken, "accountToken");
    Objects.requireNonNull(counterpartyToken, "counterpartyToken");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(amountRwf, "amountRwf");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(merchantCategoryCode, "merchantCategoryCode");
    Objects.requireNonNull(transactionTimestamp, "transactionTimestamp");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (channel == Channel.AGENT_BANKING && agentToken == null) {
      throw new IllegalArgumentException("AGENT_BANKING transactions carry an agent token");
    }
  }

  /**
   * The device token.
   *
   * @return the token, or empty when there is no fingerprint (D-04)
   */
  public Optional<String> device() {
    return Optional.ofNullable(deviceToken);
  }
}
