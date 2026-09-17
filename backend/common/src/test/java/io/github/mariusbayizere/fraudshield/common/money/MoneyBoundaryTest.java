package io.github.mariusbayizere.fraudshield.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Explicit boundary cases for the fixed-seed money tests that replaced jqwik (ADR 0009, D-17).
 *
 * <p>The seeded cases in {@link MoneyTest} sample the interior of the value space; these pin its
 * edges: zero where a positive amount is required, the smallest storable and minor-unit amounts,
 * the {@code DECIMAL(18,4)} maximum, rounding at each EAC currency's minor unit, and negatives.
 */
@Tag("D-17")
@Tag("D-43")
class MoneyBoundaryTest {

  @Test
  void zeroIsStorableButRejectedWherePositiveAmountRequired() {
    Money zero = Money.of("0", CurrencyCode.RWF);
    assertThat(zero.isPositive()).isFalse();
    assertThatThrownBy(zero::requirePositive)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be greater than zero");
    assertThatThrownBy(() -> Money.of("0.0000", CurrencyCode.KES).requirePositive())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.0001", "-1", "-99999999999999.9999"})
  void negativeAmountsAreStorableButRejectedWherePositiveAmountRequired(String amount) {
    Money negative = Money.of(amount, CurrencyCode.UGX);
    assertThat(negative.amount()).isEqualByComparingTo(amount);
    assertThatThrownBy(negative::requirePositive).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(CurrencyCode.class)
  void smallestStorableAmountIsOneTenThousandthInEveryCurrency(CurrencyCode currency) {
    Money smallest = new Money(Money.SMALLEST_STORABLE_MAGNITUDE, currency);
    assertThat(smallest.requirePositive()).isSameAs(smallest);
    assertThatThrownBy(() -> Money.of("0.00001", currency))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4 decimal places");
  }

  @ParameterizedTest
  @CsvSource({"RWF, 1", "UGX, 1", "BIF, 1", "KES, 0.01", "TZS, 0.01", "CDF, 0.01", "USD, 0.01"})
  void smallestMinorUnitPerCurrency(CurrencyCode currency, String expected) {
    Money unit = Money.smallestMinorUnit(currency);
    assertThat(unit.amount()).isEqualByComparingTo(expected);
    assertThat(unit.displayAmount()).isEqualByComparingTo(expected);
    assertThat(unit.displayAmount().scale()).isEqualTo(currency.minorUnitDigits());
  }

  @Test
  void largestDecimal18Scale4IsAcceptedAndOneStepBeyondIsRejected() {
    Money largest = new Money(Money.LARGEST_STORABLE_MAGNITUDE, CurrencyCode.TZS);
    assertThat(largest.amount().precision()).isEqualTo(Money.STORAGE_PRECISION);
    assertThat(largest.amount().scale()).isEqualTo(Money.STORAGE_SCALE);
    Money step = new Money(Money.SMALLEST_STORABLE_MAGNITUDE, CurrencyCode.TZS);
    assertThatThrownBy(() -> largest.plus(step))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DECIMAL(18,4)");
    assertThatThrownBy(() -> Money.of("-100000000000000", CurrencyCode.TZS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} {1} displays as {2}")
  @CsvSource({
    // 0 decimals: ties go to the even whole unit
    "RWF, 0.5, 0",
    "RWF, 1.5, 2",
    "RWF, 0.4999, 0",
    "RWF, 0.0001, 0",
    "UGX, 2.5, 2",
    "UGX, 3.5, 4",
    "UGX, -2.5, -2",
    // 2 decimals: ties go to the even hundredth
    "KES, 0.005, 0.00",
    "KES, 0.015, 0.02",
    "KES, 0.0049, 0.00",
    "TZS, 10.125, 10.12",
    "TZS, 10.135, 10.14",
    "CDF, 0.0051, 0.01",
    "CDF, -0.015, -0.02"
  })
  void roundingAtEachCurrencyMinorUnit(CurrencyCode currency, String amount, String expected) {
    BigDecimal display = Money.of(amount, currency).displayAmount();
    assertThat(display).isEqualByComparingTo(expected);
    assertThat(display.scale()).isEqualTo(currency.minorUnitDigits());
  }
}
