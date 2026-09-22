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
 * @param counterpartyOtherSenders24h distinct other accounts that paid the counterparty in the
 *     prior 24 hours; counted by the store so the read does not grow with a merchant's traffic
 * @param deviceAccounts7d distinct accounts seen on the transaction's device in the prior 7 days; 0
 *     without a device
 * @param deviceOtherAccounts7d as {@code deviceAccounts7d}, excluding the transaction's account
 * @param deviceFirstSeenAt when the device was first seen; null when unknown or no device
 * @param agentCustomers1h (customer account, time) pairs at the agent; empty unless agent banking
 */
public record HistoryInputs(
    List<Arrival> account,
    Arrival last,
    Instant firstSeenAt,
    Instant openedAt,
    int counterpartyOtherSenders24h,
    int deviceAccounts7d,
    int deviceOtherAccounts7d,
    Instant deviceFirstSeenAt,
    List<Map.Entry<String, Instant>> agentCustomers1h) {

  /** Copies the lists and checks the counts. */
  public HistoryInputs {
    account = List.copyOf(Objects.requireNonNull(account, "account"));
    if (counterpartyOtherSenders24h < 0
        || deviceOtherAccounts7d < 0
        || deviceOtherAccounts7d > deviceAccounts7d) {
      throw new IllegalArgumentException("distinct counts must satisfy 0 <= other <= all");
    }
    agentCustomers1h = List.copyOf(agentCustomers1h);
  }
}
