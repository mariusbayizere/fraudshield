package io.github.mariusbayizere.fraudshield.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The API-key defaults a deployment gets when it sets only the peppers, bound the way the
 * application binds them. The lifecycle tests pass their own durations, so without these a changed
 * default would go unnoticed.
 */
class AuthPropertiesTest {

  private static AuthProperties.ApiKeys apiKeysWithOnlyPeppers() {
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of("fraudshield.auth.api-keys.peppers.1", "00".repeat(32))))
        .bind("fraudshield.auth", AuthProperties.class)
        .get()
        .apiKeys();
  }

  @Test
  @Tag("FR-06-07")
  void rotatedKeysOverlapForTwentyFourHoursByDefault() {
    assertThat(apiKeysWithOnlyPeppers().rotationOverlap()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  @Tag("FR-06-07")
  void keyRecordsAreCachedForTwoSecondsByDefault() {
    // Two seconds keeps a revoked key's worst case inside FR-06-07's five (ADR 0071 section 6).
    assertThat(apiKeysWithOnlyPeppers().cacheTtl()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  @Tag("D-15")
  void verifiedKeyOutlivesItsCacheForTenMinutesWhileTheDatabaseIsDownByDefault() {
    // The owner's cap (2026-10-17): long enough for an ordinary outage, short enough to bound
    // R-13's unannounced-revocation window.
    assertThat(apiKeysWithOnlyPeppers().staleIfError()).isEqualTo(Duration.ofMinutes(10));
  }
}
