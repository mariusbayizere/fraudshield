package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * I17's clock (docs/architecture/decision-fact-ordering.md, 12.3), on explicit times: F4 time is
 * the wall time since the first F4 failure minus every interval from an F1 failure to the next
 * attempt; F1 failures neither count nor clear; a resumed state carries its count and time.
 */
@Tag("NFR-REL-01")
class HeadRetryTest {

  private static final long S = 1_000_000_000L;

  @Test
  void outageIntervalsAreNotF4Time() {
    HeadRetry retry = new HeadRetry();
    retry.failedOwn(0);
    retry.failedOutage(1 * S);
    retry.attemptStarts(4 * S);
    assertThat(retry.millis(5 * S))
        .as("5 s since the first F4, 3 s of it an outage")
        .isEqualTo(2000);
    retry.failedOutage(6 * S);
    assertThat(retry.millis(9 * S))
        .as("an outage still in progress is not counted")
        .isEqualTo(3000);
  }

  @Test
  void anOutageBeforeTheFirstF4FailureIsNotSubtracted() {
    HeadRetry retry = new HeadRetry();
    retry.failedOutage(0);
    retry.attemptStarts(10 * S);
    retry.failedOwn(10 * S);
    assertThat(retry.millis(12 * S)).isEqualTo(2000);
  }

  @Test
  void outagesNeitherCountNorClear() {
    HeadRetry retry = new HeadRetry();
    retry.failedOwn(0);
    retry.failedOutage(S);
    retry.attemptStarts(2 * S);
    retry.failedOutage(3 * S);
    retry.attemptStarts(4 * S);
    retry.failedOwn(4 * S);
    assertThat(retry.count()).isEqualTo(2);
    assertThat(retry.bounded(2, 0, 4 * S)).isTrue();
    assertThat(retry.bounded(3, 0, 4 * S)).isFalse();
  }

  @Test
  void theBoundNeedsBothItsAttemptsAndItsTime() {
    HeadRetry retry = new HeadRetry();
    retry.failedOwn(0);
    retry.failedOwn(S);
    assertThat(retry.bounded(2, 5000, 2 * S)).as("attempts, not time").isFalse();
    assertThat(retry.bounded(3, 1000, 2 * S)).as("time, not attempts").isFalse();
    assertThat(retry.bounded(2, 1000, 2 * S)).isTrue();
  }

  @Test
  void resumedStatesCarryTheirCountAndTimeButNotTheUnownedTime() {
    HeadRetry resumed = new HeadRetry(3, 9000, 100 * S);
    assertThat(resumed.count()).isEqualTo(3);
    assertThat(resumed.millis(101 * S)).isEqualTo(10_000);
    assertThat(new HeadRetry(0, 0, 100 * S).millis(200 * S)).as("nothing to resume").isZero();
  }
}
