package io.github.mariusbayizere.fraudshield.notify.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.FeatureContribution;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SmsPolicyTest {

  private static final Instant AT = Instant.parse("2026-09-22T08:15:30Z");
  private static final UUID BLOCK = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

  /** The scorer's feature values for a customer on a known device (ADR 0033). */
  private static final Map<String, FieldValue> SAFE = features(30.0, 0.0, 0.0);

  private static Map<String, FieldValue> features(
      Double simSwapDays, Double deviceIsNew, Double deviceChanges) {
    Map<String, FieldValue> values = new HashMap<>();
    values.put("days_since_sim_swap", value(simSwapDays));
    values.put("device_is_new_for_account", value(deviceIsNew));
    values.put("device_changes_24h", value(deviceChanges));
    return values;
  }

  private static FieldValue value(Double number) {
    return number == null ? FieldValue.MISSING : FieldValue.of(number);
  }

  private static Scoring.Model model(
      double score, Map<String, FieldValue> features, String... contributions) {
    List<FeatureContribution> top =
        java.util.Arrays.stream(contributions)
            .map(f -> new FeatureContribution(f, 1, true))
            .toList();
    return new Scoring.Model(UUID.randomUUID(), "m", score, 0.1, top, features);
  }

  private static SelfServicePolicy.Eligibility evaluate(Scoring scoring) {
    return SelfServicePolicy.evaluate(scoring, true);
  }

  private static Transaction transaction(String currency, double longitude) {
    return new Transaction(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "tok_AccountAaaaBbbbCccc4821",
        "tok_CounterpartyAaaaBbbbCccc1",
        Money.of("15000", CurrencyCode.valueOf(currency)),
        new BigDecimal("15000"),
        Channel.MOBILE_MONEY,
        "4829",
        -1.9,
        longitude,
        "tok_DeviceAaaaBbbbCcccDddd01",
        null,
        "RW",
        AT,
        AT);
  }

  @Test
  @Tag("D-25")
  @Tag("FR-03-04")
  void selfServiceIsAllowedOnlyWhenEveryConditionIsSafe() {
    assertThat(evaluate(model(0.9, SAFE)).allowed()).isTrue();
    assertThat(SelfServicePolicy.evaluate(model(0.9, features(30.0, null, null)), false).allowed())
        .as("USSD: no device, nothing changed")
        .isTrue();
    assertThat(evaluate(model(0.9, features(6.0, 0.0, 0.0))).refusals())
        .containsExactly(SelfServicePolicy.Refusal.SIM_SWAP_RECENT_OR_UNKNOWN);
    assertThat(evaluate(model(0.9, features(7.0, 0.0, 0.0))).allowed()).isTrue();
    assertThat(evaluate(model(0.9, features(30.0, 1.0, 0.0))).refusals())
        .containsExactly(SelfServicePolicy.Refusal.DEVICE_CHANGED);
    assertThat(evaluate(model(0.9, features(30.0, 0.0, 1.0))).refusals())
        .containsExactly(SelfServicePolicy.Refusal.DEVICE_CHANGED);
    assertThat(evaluate(model(0.9, features(30.0, null, 0.0))).refusals())
        .as("a fingerprinted payment with unknown device state is treated as changed")
        .containsExactly(SelfServicePolicy.Refusal.DEVICE_CHANGED);
    assertThat(evaluate(model(0.95, SAFE)).refusals())
        .containsExactly(SelfServicePolicy.Refusal.SCORE_TOO_HIGH);
    assertThat(evaluate(model(0.9, SAFE, "days_since_sim_swap")).refusals())
        .containsExactly(SelfServicePolicy.Refusal.TAKEOVER_SIGNALS);
    Scoring.Fallback fallback =
        new Scoring.Fallback(UUID.randomUUID(), "fallback-rules-2", RiskTier.MEDIUM, List.of());
    assertThat(evaluate(fallback).refusals())
        .containsExactly(
            SelfServicePolicy.Refusal.SIM_SWAP_RECENT_OR_UNKNOWN,
            SelfServicePolicy.Refusal.DEVICE_CHANGED,
            SelfServicePolicy.Refusal.MODEL_UNAVAILABLE);
  }

  /**
   * Intended behaviour, not a gap (D-25 point 2): until an MNO SIM-swap adapter exists the scorer
   * returns {@code days_since_sim_swap} as missing, and a missing recency is unsafe, so no block
   * offers the self-service link, however safe everything else is. The customer is told to call the
   * institution or visit a branch or agent.
   */
  @Test
  @Tag("D-25")
  @Tag("FR-03-04")
  void withoutTheSimSwapSignalNoBlockOffersSelfService() {
    for (double score : new double[] {0.85, 0.9, 0.94}) {
      for (boolean hasDevice : new boolean[] {true, false}) {
        SelfServicePolicy.Eligibility eligibility =
            SelfServicePolicy.evaluate(model(score, features(null, 0.0, 0.0)), hasDevice);
        assertThat(eligibility.allowed()).isFalse();
        assertThat(eligibility.refusals())
            .containsExactly(SelfServicePolicy.Refusal.SIM_SWAP_RECENT_OR_UNKNOWN);
      }
    }
    assertThat(
            new CustomerSmsPolicy("en")
                .compose(transaction("RWF", 30.06), BLOCK, model(0.9, features(null, 0.0, 0.0)), AT)
                .verificationLinkAllowed())
        .isFalse();
  }

  @Test
  @Tag("FR-03-04")
  @Tag("D-25")
  void theIntentCarriesEverythingTheContractRequiresAndNoContactDetails() {
    DecisionEvent.CustomerNotificationRequested intent =
        new CustomerSmsPolicy("en").compose(transaction("RWF", 30.06), BLOCK, model(0.9, SAFE), AT);
    assertThat(intent.templateKey()).matches("^sms\\.[a-z_]+$");
    assertThat(intent.maskedAccount()).isEqualTo("***4821").matches("^\\*{3,}[0-9A-Za-z]{2,4}$");
    assertThat(intent.localTime())
        .isEqualTo("10:15 CAT")
        .matches("^([01][0-9]|2[0-3]):[0-5][0-9] [A-Z]{3,4}$");
    assertThat(intent.referenceCode()).matches("^[A-Z0-9]{6,10}$");
    assertThat(intent.verificationLinkAllowed()).isTrue();
    assertThat(intent.autoBlockEventId()).isEqualTo(BLOCK);
    assertThat(
            new CustomerSmsPolicy("rw")
                .compose(transaction("RWF", 30.06), BLOCK, model(0.97, SAFE), AT)
                .verificationLinkAllowed())
        .isFalse();
    assertThatThrownBy(() -> new CustomerSmsPolicy("de"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} at {1}E -> {2}")
  @Tag("D-43")
  @CsvSource({
    "RWF, 30.06, 10:15 CAT",
    "BIF, 29.36, 10:15 CAT",
    "KES, 36.82, 11:15 EAT",
    "TZS, 39.28, 11:15 EAT",
    "UGX, 32.58, 11:15 EAT",
    "CDF, 15.31, 09:15 WAT",
    "CDF, 27.48, 10:15 CAT",
    "USD, 30.06, 08:15 UTC"
  })
  void localTimeCarriesTheZoneAbbreviation(String currency, double longitude, String expected) {
    assertThat(LocalTimes.format(AT, LocalTimes.zone(CurrencyCode.valueOf(currency), longitude)))
        .isEqualTo(expected);
  }

  @Test
  @Tag("D-25")
  void referenceCodesAreStableShortAndUnambiguous() {
    String code = ReferenceCodes.of(BLOCK);
    assertThat(code).matches("^[0-9A-HJKMNP-TV-Z]{8}$").isEqualTo(ReferenceCodes.of(BLOCK));
    assertThat(ReferenceCodes.of(UUID.randomUUID())).isNotEqualTo(code);
  }

  @ParameterizedTest(name = "{0}")
  @Tag("FR-03-04")
  @Tag("D-43")
  @ValueSource(strings = {"en", "rw", "fr", "sw"})
  void everyMessageIsOneGsm7SegmentAtWorstCaseLengths(String locale) {
    SmsCatalogue catalogue = new SmsCatalogue();
    String link =
        "https://" + "v".repeat(SmsCatalogue.MAX_LINK_LENGTH - 8 - 25) + "/v/" + "A".repeat(22);
    assertThat(link).hasSize(SmsCatalogue.MAX_LINK_LENGTH);
    SmsCatalogue.Values worst =
        new SmsCatalogue.Values(
            Money.of("99999999999999.99", CurrencyCode.KES),
            "***ABCD",
            "23:59 CAT",
            "ABCD1234",
            "+" + "9".repeat(15),
            link);
    String withLink = catalogue.autoBlock(locale, worst);
    assertThat(Gsm7.septets(withLink)).isBetween(1, 160);
    assertThat(withLink).contains(link, "ABCD1234", "***ABCD", "23:59 CAT", "+999999999999999");
    String withoutLink =
        catalogue.autoBlock(
            locale,
            new SmsCatalogue.Values(
                worst.amount(),
                worst.maskedAccount(),
                worst.localTime(),
                worst.referenceCode(),
                worst.officialPhone(),
                null));
    assertThat(Gsm7.septets(withoutLink)).isBetween(1, 160);
    assertThat(withoutLink).doesNotContain("https://");
    for (String text : List.of(withLink, withoutLink)) {
      assertThat(text.toLowerCase(java.util.Locale.ROOT))
          .doesNotContain("pin", "password", "mot de passe", "nenosiri", "ijambo");
    }
    assertThat(catalogue.status(locale))
        .as("no text has had native-speaker review")
        .isEqualTo(SmsCatalogue.Status.MACHINE_DRAFT);
  }

  @Test
  @Tag("FR-03-04")
  void nonCompliantLinksAndTextsAreRefused() {
    SmsCatalogue catalogue = new SmsCatalogue();
    SmsCatalogue.Values http =
        new SmsCatalogue.Values(
            Money.of("1", CurrencyCode.RWF),
            "***ABCD",
            "10:00 CAT",
            "ABCD1234",
            "+250788000000",
            "http://bank.rw/v/x");
    assertThatThrownBy(() -> catalogue.autoBlock("en", http))
        .isInstanceOf(IllegalArgumentException.class);
    SmsCatalogue.Values unicode =
        new SmsCatalogue.Values(
            Money.of("1", CurrencyCode.RWF), "***ABCD", "10:00 CAT", "ABCD1234", "☎ 100", null);
    assertThatThrownBy(() -> catalogue.autoBlock("en", unicode))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> catalogue.autoBlock("de", unicode))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Gsm7.septets("€[]")).isEqualTo(6);
    assertThat(Gsm7.fitsOneSegment("a".repeat(161))).isFalse();
    assertThat(Gsm7.septets("ê")).isEqualTo(-1);
  }
}
