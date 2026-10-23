package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The webhook endpoint takes its URL and its first secret from the active key, even when the key
 * rotating out sorts first because both were created in the same instant (review of round 3, N2).
 */
class WebhookOrderTest {

  private static ApiKeyRecord key(String state, Instant created) {
    return new ApiKeyRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "abcdefghijkl",
        "Core banking",
        List.of(ApiKeyScope.INGEST_WRITE),
        "abcd",
        state,
        "https://hooks.bank.example/fs",
        created,
        null,
        null);
  }

  @Test
  void activeKeysComeFirstAndNewestFirstOrderIsKeptWithinEachState() {
    Instant now = Instant.parse("2026-10-17T12:00:00Z");
    ApiKeyRecord rotating = key("ROTATING", now);
    ApiKeyRecord active = key("ACTIVE", now);
    ApiKeyRecord olderActive = key("ACTIVE", now.minusSeconds(60));
    // As the repository lists them, newest first, with the tie broken the wrong way.
    assertThat(
            List.of(rotating, active, olderActive).stream()
                .sorted(ApiKeyService.ACTIVE_FIRST)
                .toList())
        .containsExactly(active, olderActive, rotating);
  }
}
