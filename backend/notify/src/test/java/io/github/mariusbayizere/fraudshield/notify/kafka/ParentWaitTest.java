package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A record waits for its parent fact for {@link EnvelopeConsumer#PARENT_WAIT}, and no longer. */
@Tag("FR-03-04")
class ParentWaitTest {

  private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

  @Test
  void recordsWaitForTheirParentUntilTenMinutesOld() {
    long written = NOW.toEpochMilli();
    assertThat(EnvelopeConsumer.stillWaiting(written, NOW)).isTrue();
    assertThat(
            EnvelopeConsumer.stillWaiting(
                written, NOW.plus(EnvelopeConsumer.PARENT_WAIT).minusMillis(1)))
        .isTrue();
    assertThat(EnvelopeConsumer.stillWaiting(written, NOW.plus(EnvelopeConsumer.PARENT_WAIT)))
        .isFalse();
  }

  @Test
  void recordsWithoutTimestampsDoNotWait() {
    assertThat(EnvelopeConsumer.stillWaiting(-1, NOW)).isFalse();
  }
}
