package io.github.mariusbayizere.fraudshield.auth.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The test helper's employee IDs always fit the API's pattern, whatever value it draws. */
class EmployeeIdsTest {

  private static final String PATTERN = "E[0-9A-Z]{10}";

  @Test
  void valuesWithFewerThanTenDigitsArePaddedNotTruncated() {
    // 36^9 - 1 has nine base-36 digits, the case that threw StringIndexOutOfBounds on main.
    long nineDigits = 101_559_956_668_415L;
    assertThat(Long.toString(nineDigits, 36)).hasSize(9);

    assertThat(EmployeeIds.of(nineDigits)).isEqualTo("E0ZZZZZZZZZ").matches(PATTERN);
    assertThat(EmployeeIds.of(0)).isEqualTo("E0000000000");
  }

  @Test
  void negativeValuesGiveNoMinusSign() {
    assertThat(EmployeeIds.of(Long.MIN_VALUE)).matches(PATTERN);
    assertThat(EmployeeIds.of(-1)).matches(PATTERN);
  }

  @Test
  void everyIdHasTheSameShape() {
    SplittableRandom random = new SplittableRandom(20_261_020L);
    for (int i = 0; i < 100_000; i++) {
      assertThat(EmployeeIds.of(random.nextLong())).matches(PATTERN);
    }
  }
}
