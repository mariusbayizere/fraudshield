package io.github.mariusbayizere.fraudshield.decision.domain.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the online store read for one transaction, before any feature arithmetic.
 *
 * @param account the account's arrivals in the retained horizon (90 days)
 * @param last the account's latest earlier transaction, kept beyond the horizon; null if none
 * @param firstSeenAt the account's durable first-seen time (PB-37); null when unknown
 * @param openedAt the account's opening date; null when not supplied
 * @param counterpartySenders24h (sender account, time) pairs paying the counterparty
 * @param deviceAccounts7d (account, time) pairs on the transaction's device; empty without one
 * @param deviceFirstSeenAt when the device was first seen; null when unknown or no device
 * @param agentCustomers1h (customer account, time) pairs at the agent; empty unless agent banking
 */
public record HistoryInputs(
    List<Arrival> account,
    Arrival last,
    Instant firstSeenAt,
    Instant openedAt,
    List<Map.Entry<String, Instant>> counterpartySenders24h,
    List<Map.Entry<String, Instant>> deviceAccounts7d,
    Instant deviceFirstSeenAt,
    List<Map.Entry<String, Instant>> agentCustomers1h) {

  /** Copies the lists. */
  public HistoryInputs {
    account = List.copyOf(Objects.requireNonNull(account, "account"));
    counterpartySenders24h = List.copyOf(counterpartySenders24h);
    deviceAccounts7d = List.copyOf(deviceAccounts7d);
    agentCustomers1h = List.copyOf(agentCustomers1h);
  }
}
