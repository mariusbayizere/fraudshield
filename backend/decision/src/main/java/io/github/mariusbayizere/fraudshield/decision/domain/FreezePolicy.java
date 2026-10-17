package io.github.mariusbayizere.fraudshield.decision.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Account freeze (FR-03-06, E.6): the third HIGH decision for one account within a sliding 60
 * minutes freezes it. Counted by decision time, so a burst of backdated transactions cannot dodge
 * the window.
 */
public final class FreezePolicy {

  /** HIGH decisions within the window that freeze an account. */
  public static final int HIGH_DECISIONS_TO_FREEZE = 3;

  /** The sliding window. */
  public static final Duration WINDOW = Duration.ofMinutes(60);

  private FreezePolicy() {}

  /**
   * Whether the latest HIGH decision freezes the account.
   *
   * @param highDecisionTimes decision times of HIGH decisions for the account, including the latest
   * @param latest the latest decision's time
   * @return true when at least three fall in {@code (latest - 60 min, latest]}
   */
  public static boolean freezes(List<Instant> highDecisionTimes, Instant latest) {
    Instant start = latest.minus(WINDOW);
    long inWindow =
        highDecisionTimes.stream().filter(t -> t.isAfter(start) && !t.isAfter(latest)).count();
    return inWindow >= HIGH_DECISIONS_TO_FREEZE;
  }
}
