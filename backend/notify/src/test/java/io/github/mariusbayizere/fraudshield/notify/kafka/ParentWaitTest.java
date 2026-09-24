package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A record waiting for its parent is reported after a minute, then every minute (ADR 0057). */
@Tag("FR-03-04")
class ParentWaitTest {

  private static final long MINUTE = EnvelopeConsumer.WARN_AFTER.toNanos();

  @Test
  void waitsAreReportedAfterOneMinuteThenEveryMinute() {
    long since = 1_000_000_000L;
    assertThat(EnvelopeConsumer.reportDue(since, since, since)).isFalse();
    assertThat(EnvelopeConsumer.reportDue(since, since, since + MINUTE - 1)).isFalse();
    assertThat(EnvelopeConsumer.reportDue(since, since, since + MINUTE)).isTrue();

    long reported = since + MINUTE;
    assertThat(EnvelopeConsumer.reportDue(since, reported, reported + MINUTE - 1)).isFalse();
    assertThat(EnvelopeConsumer.reportDue(since, reported, reported + MINUTE)).isTrue();
  }
}
