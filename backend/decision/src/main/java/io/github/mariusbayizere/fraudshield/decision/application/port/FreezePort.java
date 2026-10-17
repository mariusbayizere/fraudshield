package io.github.mariusbayizere.fraudshield.decision.application.port;

import java.time.Instant;
import java.util.UUID;

/** HIGH decisions per account in a sliding hour, and the freeze they trigger (FR-03-06). */
public interface FreezePort {

  /**
   * Records a HIGH decision and freezes the account atomically if it is the third within the
   * window. Exactly one caller wins the freeze when several race.
   *
   * @param institutionId institution
   * @param accountToken account
   * @param transactionId the HIGH transaction
   * @param decidedAt decision time
   * @return HIGH decisions in the window, and whether this call froze the account
   */
  FreezeCheck recordHigh(
      UUID institutionId, String accountToken, UUID transactionId, Instant decidedAt);

  /**
   * The result of recording a HIGH decision.
   *
   * @param highDecisionsInWindow HIGH decisions in the last 60 minutes, this one included
   * @param frozeNow whether this call froze the account
   */
  record FreezeCheck(int highDecisionsInWindow, boolean frozeNow) {}
}
