package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;

/**
 * The online feature store (FR-02-09): account history before scoring and its update after the
 * decision. One pipelined round-trip each in Redis (C.2 step 3); PostgreSQL when Redis is down
 * (C.4).
 */
public interface AccountStatePort {

  /**
   * An account's state as of just before the transaction, and whether the account is frozen.
   *
   * @param history the account history, the transaction itself excluded
   * @param frozen whether the account is frozen (E.6 step 2)
   * @param firstSeen whether the account has never transacted in FraudShield (PB-37)
   */
  record Snapshot(AccountHistory history, boolean frozen, boolean firstSeen) {}

  /**
   * Reads the account's state.
   *
   * @param transaction the transaction about to be scored
   * @return history and freeze status
   */
  Snapshot read(Transaction transaction);

  /**
   * Adds the decided transaction to the account's state, so the next transaction sees it.
   *
   * @param transaction the decided transaction
   */
  void record(Transaction transaction);
}
