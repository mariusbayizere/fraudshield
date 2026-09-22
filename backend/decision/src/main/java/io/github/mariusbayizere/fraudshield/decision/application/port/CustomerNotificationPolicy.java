package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.time.Instant;
import java.util.UUID;

/**
 * Composes the customer SMS intent for an auto-block (FR-03-04), including whether self-service
 * verification is allowed (D-25). Implemented by the notify module.
 */
public interface CustomerNotificationPolicy {

  /**
   * Composes the intent.
   *
   * @param transaction the blocked transaction
   * @param autoBlockEventId the block
   * @param scoring the scoring the block was based on
   * @param history the account state before the transaction
   * @param at request time
   * @return the intent
   */
  DecisionEvent.CustomerNotificationRequested compose(
      Transaction transaction,
      UUID autoBlockEventId,
      Scoring scoring,
      AccountHistory history,
      Instant at);
}
