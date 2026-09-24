package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A record waits for its parent for as long as PostgreSQL answers without it, up to a bound. */
@Tag("FR-03-04")
class ParentWaitTest {

  @Test
  void recordsWaitUntilPostgresHasAnsweredWithoutTheirParentForTheWholeWait() {
    long since = 1_000_000_000L;
    Duration wait = EnvelopeConsumer.PARENT_WAIT;
    assertThat(EnvelopeConsumer.stillWaiting(since, since, wait)).isTrue();
    assertThat(EnvelopeConsumer.stillWaiting(since, since + wait.toNanos() - 1, wait)).isTrue();
    assertThat(EnvelopeConsumer.stillWaiting(since, since + wait.toNanos(), wait)).isFalse();
  }

  @Test
  void theWaitIsHalfAnHourAndItsRetriesDoNotBackOff() {
    assertThat(EnvelopeConsumer.PARENT_WAIT).isEqualTo(Duration.ofMinutes(30));
    assertThat(EnvelopeConsumer.PARENT_BACKOFF_MS).isLessThan(EnvelopeConsumer.MAX_BACKOFF_MS);
  }
}
