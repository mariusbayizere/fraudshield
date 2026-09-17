package io.github.mariusbayizere.fraudshield.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MoneyTest {

  @Tag("D-43")
  @ParameterizedTest
  @EnumSource(CurrencyCode.class)
  void minorUnitTableAgreesWithIso4217DataInTheJdk(CurrencyCode code) {
    assertThat(code.minorUnitDigits())
        .isEqualTo(Currency.getInstance(code.name()).getDefaultFractionDigits());
  }

  @Tag("D-43")
  @ParameterizedTest
  @CsvSource({
    "1250.5, RWF, 1250",
    "1251.5, RWF, 1252",
    "99.995, KES, 100.00",
    "1000, UGX, 1000",
    "0.0049, CDF, 0.00",
    "0.125, KES, 0.12",
    "0.135, KES, 0.14",
    "-0.125, KES, -0.12"
  })
  void displayAmountRoundsToMinorUnitsWithBankersRounding(
      String amount, CurrencyCode currency, String expected) {
    assertThat(Money.of(amount, currency).displayAmount()).isEqualByComparingTo(expected);
    assertThat(Money.of(amount, currency).displayAmount().scale())
        .isEqualTo(currency.minorUnitDigits());
  }

  @Tag("D-43")
  @Test
  void storesAtDecimal18Scale4AndRejectsWhatCannotBeStoredExactly() {
    assertThat(Money.of("15000", CurrencyCode.RWF).amount())
        .isEqualTo(new BigDecimal("15000.0000"));
    assertThat(Money.of("99999999999999.9999", CurrencyCode.KES).amount().precision())
        .isEqualTo(18);
    assertThat(Money.of("1.50000", CurrencyCode.KES).amount()).isEqualTo(new BigDecimal("1.5000"));

    assertThatThrownBy(() -> Money.of("1.00001", CurrencyCode.KES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4 decimal places");
    assertThatThrownBy(() -> Money.of("100000000000000", CurrencyCode.RWF))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DECIMAL(18,4)");
  }

  @Test
  void equalAmountsWrittenDifferentlyAreEqual() {
    assertThat(Money.of("10.5", CurrencyCode.TZS)).isEqualTo(Money.of("10.5000", CurrencyCode.TZS));
  }

  @Test
  void additionRequiresTheSameCurrency() {
    Money rwf = Money.of("1000", CurrencyCode.RWF);
    assertThat(rwf.plus(Money.of("500", CurrencyCode.RWF)))
        .isEqualTo(Money.of("1500", CurrencyCode.RWF));
    assertThatThrownBy(() -> rwf.plus(Money.of("1", CurrencyCode.KES)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency mismatch");
  }

  @Test
  void positivityAndParsing() {
    assertThat(Money.of("0.0001", CurrencyCode.USD).isPositive()).isTrue();
    assertThat(Money.of("0", CurrencyCode.USD).isPositive()).isFalse();
    assertThat(CurrencyCode.parse("RWF")).contains(CurrencyCode.RWF);
    assertThat(CurrencyCode.parse("rwf")).isEmpty();
    assertThat(CurrencyCode.parse("XXX")).isEmpty();
    assertThat(CurrencyCode.parse("RWFX")).isEmpty();
    assertThat(CurrencyCode.parse(null)).isEmpty();
  }

  /** Fixed seed: generated cases are identical on every run and every machine. */
  private static final long PROPERTY_SEED = 20260917L;

  private static final int PROPERTY_CASES = 1000;

  /** Maximum amount in ten-thousandths (10^9 whole units at scale 4). */
  private static final long MAX_SCALED_AMOUNT = 10_000_000_000_000L;

  static Stream<BigDecimal[]> storableAmountPairs() {
    SplittableRandom random = new SplittableRandom(PROPERTY_SEED);
    Stream<BigDecimal[]> edges =
        Stream.of(
            new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO},
            new BigDecimal[] {new BigDecimal("0.0001"), new BigDecimal("0.0001")},
            new BigDecimal[] {new BigDecimal("1000000000.0000"), new BigDecimal("0.9999")});
    Stream<BigDecimal[]> generated =
        Stream.generate(
                () ->
                    new BigDecimal[] {
                      BigDecimal.valueOf(random.nextLong(MAX_SCALED_AMOUNT + 1), 4),
                      BigDecimal.valueOf(random.nextLong(MAX_SCALED_AMOUNT + 1), 4)
                    })
            .limit(PROPERTY_CASES);
    return Stream.concat(edges, generated);
  }

  @ParameterizedTest
  @MethodSource("storableAmountPairs")
  void additionIsExactForStorableAmounts(BigDecimal a, BigDecimal b) {
    Money sum = new Money(a, CurrencyCode.RWF).plus(new Money(b, CurrencyCode.RWF));
    assertThat(sum.amount()).isEqualByComparingTo(a.add(b));
  }

  @Test
  void negativeAmountsAreRepresentableForCompensatingEntries() {
    assertThat(Money.of("-15000", CurrencyCode.RWF).isPositive()).isFalse();
    assertThat(Money.of("-15000", CurrencyCode.RWF).plus(Money.of("15000", CurrencyCode.RWF)))
        .isEqualTo(Money.of("0", CurrencyCode.RWF));
  }
}
