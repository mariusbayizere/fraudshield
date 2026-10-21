package io.github.mariusbayizere.fraudshield.auth.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The test helper's codes fit the schema's patterns and do not repeat. */
class UniqueCodesTest {

  /** {@code institutions.code} (V1). */
  private static final String INSTITUTION_CODE = "^[a-z0-9][a-z0-9-]{1,31}$";

  /** {@code api_keys.name} (V2). */
  private static final String KEY_NAME = "^[A-Za-z0-9 _.-]{3,80}$";

  @Test
  void codesFitTheInstitutionAndKeyNamePatterns() {
    assertThat(UniqueCodes.of("n1-users-")).matches(INSTITUTION_CODE).hasSize(9 + 12);
    assertThat(UniqueCodes.of("Core banking prod ")).matches(KEY_NAME);
  }

  @Test
  void codesDoNotRepeatWhereTheClockSchemeDid() {
    // 100,000 clock reads taken mod 100,000 repeat, certainly in practice: the loop spans far
    // more than 100 microseconds, so the residue wraps, and a coarser clock repeats sooner.
    Set<String> clock = new HashSet<>();
    for (int i = 0; i < 100_000; i++) {
      clock.add("self-" + System.nanoTime() % 100000);
    }
    assertThat(clock).hasSizeLessThan(100_000);

    // 1,000 codes of 48 random bits repeat with probability about 1,000^2 / 2^49, 1.8e-9.
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      codes.add(UniqueCodes.of("self-"));
    }
    assertThat(codes).hasSize(1_000);
  }
}
