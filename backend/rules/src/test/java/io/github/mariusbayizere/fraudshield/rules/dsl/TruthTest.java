package io.github.mariusbayizere.fraudshield.rules.dsl;

import static io.github.mariusbayizere.fraudshield.rules.dsl.Truth.FALSE;
import static io.github.mariusbayizere.fraudshield.rules.dsl.Truth.TRUE;
import static io.github.mariusbayizere.fraudshield.rules.dsl.Truth.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FR-05-05")
class TruthTest {

  @Test
  void conjunctionFollowsKleene() {
    assertThat(TRUE.and(TRUE)).isEqualTo(TRUE);
    assertThat(TRUE.and(FALSE)).isEqualTo(FALSE);
    assertThat(TRUE.and(UNKNOWN)).isEqualTo(UNKNOWN);
    assertThat(FALSE.and(UNKNOWN)).isEqualTo(FALSE);
    assertThat(UNKNOWN.and(FALSE)).isEqualTo(FALSE);
    assertThat(UNKNOWN.and(UNKNOWN)).isEqualTo(UNKNOWN);
    assertThat(FALSE.and(FALSE)).isEqualTo(FALSE);
  }

  @Test
  void disjunctionFollowsKleene() {
    assertThat(FALSE.or(FALSE)).isEqualTo(FALSE);
    assertThat(FALSE.or(TRUE)).isEqualTo(TRUE);
    assertThat(FALSE.or(UNKNOWN)).isEqualTo(UNKNOWN);
    assertThat(TRUE.or(UNKNOWN)).isEqualTo(TRUE);
    assertThat(UNKNOWN.or(TRUE)).isEqualTo(TRUE);
    assertThat(UNKNOWN.or(UNKNOWN)).isEqualTo(UNKNOWN);
    assertThat(TRUE.or(TRUE)).isEqualTo(TRUE);
  }

  @Test
  void negationKeepsUnknown() {
    assertThat(TRUE.not()).isEqualTo(FALSE);
    assertThat(FALSE.not()).isEqualTo(TRUE);
    assertThat(UNKNOWN.not()).isEqualTo(UNKNOWN);
    assertThat(Truth.of(true)).isEqualTo(TRUE);
    assertThat(Truth.of(false)).isEqualTo(FALSE);
  }
}
