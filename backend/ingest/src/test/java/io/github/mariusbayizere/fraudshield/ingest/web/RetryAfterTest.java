package io.github.mariusbayizere.fraudshield.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0058 point 6, review 9 of 2026-09-24: a batch refused at its second charge keeps its
 * admission unit, so a retry costs the whole batch and Retry-After is the wait for all of it.
 */
@Tag("FR-01-02")
class RetryAfterTest {

  @Test
  void theWaitCoversTheWholeBatchNotOnlyTheRefusedRest() {
    // 20 items, 4 units left after admission, 5 per second: 16 more units, four seconds. A wait
    // for the refused rest alone (19 - 4 = 15, three seconds) would be refused again on retry.
    assertThat(IngestController.retryAfterForWholeBatch(20, 4, 5)).isEqualTo(4);
    assertThat(IngestController.retryAfterForWholeBatch(20, 5, 5)).isEqualTo(3);
    assertThat(IngestController.retryAfterForWholeBatch(1_000, 0, 2_000)).isEqualTo(1);
    assertThat(IngestController.retryAfterForWholeBatch(1_000, 999, 5))
        .as("never below one second")
        .isEqualTo(1);
  }
}
