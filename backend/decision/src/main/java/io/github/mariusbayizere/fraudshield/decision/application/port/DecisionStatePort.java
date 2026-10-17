package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import java.util.Optional;
import java.util.UUID;

/** The latest decision state per transaction, for {@code GET /decisions/{id}} (D-14). */
public interface DecisionStatePort {

  /**
   * Stores a state if its sequence is exactly one more than the stored one (or it is sequence 1 and
   * nothing is stored).
   *
   * @param state the new state
   * @return false when another writer stored a state first; the caller re-reads and re-decides
   */
  boolean save(DecisionState state);

  /**
   * The latest state.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return the state, if the transaction is known
   */
  Optional<DecisionState> latest(UUID institutionId, UUID transactionId);
}
