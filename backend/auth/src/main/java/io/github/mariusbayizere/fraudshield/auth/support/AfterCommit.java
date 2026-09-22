package io.github.mariusbayizere.fraudshield.auth.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the current transaction commits, so a rolled-back or retried
 * transaction (TenantTransactions retries serialization failures) never sends an email or publishes
 * an invalidation for a change that did not happen.
 */
public final class AfterCommit {

  private AfterCommit() {}

  /**
   * Runs the action after the current transaction commits, or now if there is none.
   *
   * @param action the side effect
   */
  public static void run(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
