package io.github.mariusbayizere.fraudshield.auth.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Cost 12 bcrypt (FR-07-07) and constant-work comparisons. */
@Tag("FR-07-07")
class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void hashesWithCostTwelveTakingMoreThan100MillisecondsEach() {
    String hash = hasher.hash("Str0ng!Passw0rd");
    assertThat(hash).startsWith("$2a$12$");
    long[] millis = new long[5];
    for (int i = 0; i < millis.length; i++) {
      long start = System.nanoTime();
      hasher.hash("Str0ng!Passw0rd");
      millis[i] = (System.nanoTime() - start) / 1_000_000;
    }
    Arrays.sort(millis);
    // The minimum of five runs: a fast outlier would be the only way to fall under the bound.
    assertThat(millis[0])
        .as("bcrypt timing test (> 100 ms per hash), runs %s", Arrays.toString(millis))
        .isGreaterThan(100);
  }

  @Test
  void matchesOnlyTheRightPasswordAndNeverMissingHash() {
    String hash = hasher.hash("Str0ng!Passw0rd");
    assertThat(hasher.matches("Str0ng!Passw0rd", hash)).isTrue();
    assertThat(hasher.matches("Str0ng!Passw0rD", hash)).isFalse();
    assertThat(hasher.matches("Str0ng!Passw0rd", null)).isFalse();
    assertThat(hasher.matches("x".repeat(73), hash)).as("over 72 bytes never matches").isFalse();
  }

  @Test
  void overlongPasswordStillCostsOneBcrypt() {
    String hash = hasher.hash("Str0ng!Passw0rd");
    long start = System.nanoTime();
    assertThat(hasher.matches("x".repeat(100), hash)).isFalse();
    assertThat((System.nanoTime() - start) / 1_000_000).as("review finding 6").isGreaterThan(100);
  }

  @Test
  void missingHashStillCostsOneBcrypt() {
    long start = System.nanoTime();
    hasher.matches("Str0ng!Passw0rd", null);
    hasher.burn();
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThan(150);
  }
}
