package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.util.List;

/** The ML scorer (gRPC, {@code fraudshield.scoring.v1}; C.2 steps 4–8). */
public interface ScoringPort {

  /**
   * A scored transaction.
   *
   * @param scoring what the engine decides on
   * @param record the full result for persistence
   */
  record Scored(Scoring.Model scoring, DecisionEvent.ScoringRecord record) {}

  /**
   * Scores one transaction within the hot-path deadline.
   *
   * @param transaction the transaction; the scorer reads the account's context itself (ADR 0033)
   * @param configuredLimits channel and rule limits for {@code just_below_limit_flag}
   * @return the result
   * @throws ScorerUnavailableException when the scorer cannot answer in time or the circuit is
   *     open; the caller decides with the fallback rules (C.4)
   */
  Scored score(Transaction transaction, List<Money> configuredLimits);
}
