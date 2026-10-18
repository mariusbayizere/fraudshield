package io.github.mariusbayizere.fraudshield.decision.application.port;

import java.util.UUID;

/**
 * Whether an account is frozen (FR-03-06, E.6 step 2). Decision state that the decision path owns,
 * not a model feature: the account's behaviour reaches the decision only as the feature values the
 * scorer returns (ADR 0033, ADR 0061).
 */
public interface AccountStatusPort {

  /**
   * Whether the account is frozen.
   *
   * @param institutionId institution
   * @param accountToken account
   * @return true if the account is frozen
   */
  boolean frozen(UUID institutionId, String accountToken);
}
