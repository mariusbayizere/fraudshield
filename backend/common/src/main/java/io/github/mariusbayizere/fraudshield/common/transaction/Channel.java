package io.github.mariusbayizere.fraudshield.common.transaction;

/** Payment channels accepted at ingestion (FR-01-02); matches the OpenAPI {@code Channel} enum. */
public enum Channel {
  /** Mobile money transfer. */
  MOBILE_MONEY,
  /** Card payment. */
  CARD,
  /** Cash-in or cash-out at an agent. */
  AGENT_BANKING,
  /** USSD session from a feature phone. */
  USSD,
  /** Online card-not-present or wallet payment. */
  ONLINE,
  /** Bank transfer. */
  BANK_TRANSFER
}
