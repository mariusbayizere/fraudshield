package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.notify.kafka.WaitBudget.Kind;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The wait budget on an explicit clock (docs/architecture/decision-fact-ordering.md, section 6):
 * waiting fills it, neutral time does neither, all other time drains it, and no outcome resets it.
 */
@Tag("FR-03-04")
@Tag("NFR-REL-01")
class WaitBudgetTest {

  private static final Duration BOUND = Duration.ofMinutes(10);
  private static final double RATE = 1.0 / 6;
  private static final long MIN = Duration.ofMinutes(1).toNanos();
  private static final long SEC = Duration.ofSeconds(1).toNanos();

  private static WaitBudget empty(long now) {
    return new WaitBudget(BOUND, RATE, Duration.ZERO, now);
  }

  @Test
  void waitingFillsTheBudgetUpToTheBoundAndThenItIsFull() {
    // I7.
    WaitBudget budget = empty(0);
    budget.succeeded(0, Kind.WAITING);
    assertThat(budget.full(9 * MIN)).isFalse();
    budget.succeeded(9 * MIN, Kind.WAITING);
    assertThat(budget.spent(9 * MIN)).isEqualTo(Duration.ofMinutes(9));
    assertThat(budget.full(10 * MIN)).isTrue();
    assertThat(budget.spent(20 * MIN)).as("capped at the bound").isEqualTo(BOUND);
  }

  @Test
  void theFinalIntervalOfEachWaitCountsAndThenTheBudgetDrains() {
    // W-b: from the last S4 answer to the answer that ends the wait is waiting.
    WaitBudget budget = empty(0);
    budget.succeeded(0, Kind.WAITING);
    budget.succeeded(2 * MIN, Kind.DRAINING);
    assertThat(budget.spent(2 * MIN)).isEqualTo(Duration.ofMinutes(2));
    assertThat(budget.spent(8 * MIN)).as("six minutes drain one").isEqualTo(Duration.ofMinutes(1));
    assertThat(budget.spent(20 * MIN)).isZero();
  }

  @Test
  void orphansBetweenValidIntentsDoNotEachGetTheWholeBound() {
    // I7b (contract review 3, MAJOR 1): orphan, valid, orphan, valid... Each valid intent is
    // handled
    // at once, so almost no time drains between orphans: the second orphan finds the budget spent.
    WaitBudget budget = empty(0);
    long t = 0;
    budget.succeeded(t, Kind.WAITING);
    t += 10 * MIN;
    assertThat(budget.full(t)).isTrue();
    budget.succeeded(t, Kind.DRAINING); // dead-lettered
    t += SEC; // a valid intent, handled
    budget.succeeded(t, Kind.DRAINING);
    t += SEC; // the next orphan's first answer
    budget.succeeded(t, Kind.WAITING);
    assertThat(budget.full(t + SEC / 2))
        .as("two seconds of draining gave back a third of a second")
        .isTrue();
    // Over any window t the waiting is at most bound + t / 6: an hour of alternation costs
    // at most 10 + 10 minutes.
    WaitBudget hour = empty(0);
    long waited = 0;
    long now = 0;
    long end = 60 * MIN;
    while (now < end) {
      hour.succeeded(now, Kind.WAITING);
      long start = now;
      while (!hour.full(now) && now < end) {
        now += SEC / 2;
      }
      waited += now - start;
      hour.succeeded(now, Kind.DRAINING);
      now += 30 * SEC; // valid intents in between
      hour.succeeded(now, Kind.DRAINING);
    }
    assertThat(Duration.ofNanos(waited)).isLessThanOrEqualTo(BOUND.plus(Duration.ofMinutes(11)));
  }

  @Test
  void idleTimeAfterTrippingDrainsSoTheNextOrphanGetsTheFullBoundAgain() {
    // I7c (contract review 4, MAJOR 1).
    WaitBudget budget = empty(0);
    budget.succeeded(0, Kind.WAITING);
    budget.succeeded(10 * MIN, Kind.DRAINING);
    assertThat(budget.full(10 * MIN)).isTrue();
    long anHourLater = 70 * MIN;
    assertThat(budget.spent(anHourLater)).isZero();
    budget.succeeded(anHourLater, Kind.WAITING);
    assertThat(budget.full(anHourLater + 9 * MIN)).isFalse();
  }

  @Test
  void neutralTimeNeitherFillsNorDrainsTheBudget() {
    // I8: from the start of a failed attempt to the end of the next successful one.
    WaitBudget budget = empty(0);
    budget.succeeded(0, Kind.WAITING);
    budget.failed(3 * MIN);
    assertThat(budget.spent(3 * MIN)).isEqualTo(Duration.ofMinutes(3));
    assertThat(budget.spent(63 * MIN)).as("an hour of outage").isEqualTo(Duration.ofMinutes(3));
    budget.succeeded(63 * MIN, Kind.WAITING);
    assertThat(budget.spent(64 * MIN)).isEqualTo(Duration.ofMinutes(4));
  }

  @Test
  void theCheckpointCarriesWhatIsSpentAndNewOwnersDrainTheTimeSince() {
    // I14 (W-e).
    WaitBudget budget = empty(0);
    budget.succeeded(0, Kind.WAITING);
    String checkpoint = budget.checkpoint(42, 6 * MIN, 1_000_000L);
    assertThat(checkpoint).isEqualTo("fs-wait:v2 offset=42 spent_ms=360000 at=1000000");
    assertThat(WaitBudget.resume(checkpoint, 42, 1_000_000L, RATE).spent())
        .isEqualTo(Duration.ofMinutes(6));
    assertThat(WaitBudget.resume(checkpoint, 42, 1_000_000L + 60_000, RATE).spent())
        .as("a minute unowned drains ten seconds")
        .isEqualTo(Duration.ofMinutes(6).minusSeconds(10));
    assertThat(WaitBudget.resume(checkpoint, 43, 1_000_000L, RATE).spent())
        .as("a checkpoint for another offset is not this partition's")
        .isZero();
    assertThat(WaitBudget.resume("", 42, 1_000_000L, RATE).spent()).isZero();
    assertThat(WaitBudget.resume(null, 42, 1_000_000L, RATE).spent()).isZero();
    assertThat(empty(0).checkpoint(42, 0, 1L)).as("nothing spent, no metadata").isEmpty();
  }

  @Test
  void checkpointsFromTheFutureAreClampedNotAddedTo() {
    // I14, clock skew (contract review 4, MINOR 3).
    WaitBudget.Resumed resumed =
        WaitBudget.resume("fs-wait:v2 offset=7 spent_ms=120000 at=5000000", 7, 4_000_000L, RATE);
    assertThat(resumed.futureCheckpoint()).isTrue();
    assertThat(resumed.spent()).isEqualTo(Duration.ofMinutes(2));
  }
}
