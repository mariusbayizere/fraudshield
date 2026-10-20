package io.github.mariusbayizere.fraudshield.analyst.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.analyst.support.CountryPacks;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The behavioural fingerprint's rules, each as the contract's description states it (FR-04-07). */
@Tag("FR-04-07")
class BehaviourProfilesTest {

  private static final BehaviourProfiles PROFILES = new BehaviourProfiles(CountryPacks.load());
  private static final Instant DAY = Instant.parse("2026-09-01T06:00:00Z");

  private static BehaviourProfiles.Txn txn(
      Instant at, String amount, String currency, String channel, String counterparty) {
    return new BehaviourProfiles.Txn(
        at, new BigDecimal(amount), currency, channel, counterparty, "tok_device", "RW");
  }

  private static List<BehaviourProfiles.Txn> daily(int count) {
    List<BehaviourProfiles.Txn> window = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      window.add(txn(DAY.plusSeconds(86_400L * i), "10000", "RWF", "MOBILE_MONEY", "tok_known"));
    }
    return window;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> item(Map<String, Object> profile, int index) {
    return ((List<Map<String, Object>>) profile.get("comparison")).get(index);
  }

  @Test
  void hoursAreCountedInTheLocalTimeOfEachTransactionsCurrency() {
    List<BehaviourProfiles.Txn> window = new ArrayList<>(daily(2));
    window.add(txn(DAY, "10000", "KES", "MOBILE_MONEY", "tok_known"));
    window.add(txn(DAY, "10000", "USD", "MOBILE_MONEY", "tok_known"));

    Map<String, Object> profile = PROFILES.profile(window.getFirst(), window, "RW");

    List<?> hours = (List<?>) profile.get("hourly_counts");
    assertThat(hours.get(8))
        .as("RWF at +2, and USD, which no pack declares, at the institution's")
        .isEqualTo(3);
    assertThat(hours.get(9)).as("KES at +3").isEqualTo(1);
  }

  @Test
  void withFewerThanTenTransactionsNothingIsNormalOrAnomalous() {
    List<BehaviourProfiles.Txn> window = daily(9);
    BehaviourProfiles.Txn odd =
        txn(DAY.plusSeconds(3600 * 12), "9999999", "RWF", "CARD", "tok_new");

    Map<String, Object> profile = PROFILES.profile(odd, window, "RW");

    for (int i = 0; i < 6; i++) {
      assertThat(item(profile, i).get("anomalous")).as("item " + i).isEqualTo(false);
    }
    assertThat(item(profile, 0).get("normal")).isNull();
  }

  @Test
  void anUnusualHourAmountChannelAndCounterpartyAreEachAnomalous() {
    List<BehaviourProfiles.Txn> window = daily(20);
    BehaviourProfiles.Txn odd =
        txn(DAY.plusSeconds(3600 * 12), "9999999", "RWF", "CARD", "tok_new");

    Map<String, Object> profile = PROFILES.profile(odd, window, "RW");

    assertThat(item(profile, 0))
        .containsEntry("attribute", "local_hour")
        .containsEntry("this_transaction", 20)
        .containsEntry("normal", "08-08")
        .containsEntry("anomalous", true);
    assertThat(item(profile, 1))
        .containsEntry("attribute", "amount_rwf")
        .containsEntry("normal", "10000-10000")
        .containsEntry("anomalous", true);
    assertThat(item(profile, 2))
        .containsEntry("normal", "MOBILE_MONEY")
        .containsEntry("anomalous", true);
    assertThat(item(profile, 3))
        .containsEntry("this_transaction", 0L)
        .containsEntry("anomalous", true);
    assertThat(item(profile, 5)).containsEntry("normal", "RW").containsEntry("anomalous", false);
  }

  @Test
  void theHourRangeIsTheShortestHoldingNinetyPercentAndMayWrapMidnight() {
    int[] hours = new int[24];
    hours[22] = 5;
    hours[23] = 3;
    hours[0] = 2;
    hours[12] = 1;

    assertThat(BehaviourProfiles.hourRange(hours, 11)).containsExactly(22, 0);
  }

  @Test
  void amountsAreBucketedHalfOpenAcrossTheRangeTheyCover() {
    List<BehaviourProfiles.Txn> window =
        List.of(
            txn(DAY, "999", "RWF", "USSD", "tok_a"),
            txn(DAY, "1000", "RWF", "USSD", "tok_a"),
            txn(DAY, "60000", "RWF", "USSD", "tok_a"));

    Map<String, Object> profile = PROFILES.profile(window.getFirst(), window, "RW");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buckets = (List<Map<String, Object>>) profile.get("amount_histogram");
    assertThat(buckets.getFirst())
        .containsEntry("lower_rwf", "0")
        .containsEntry("upper_rwf", "1000")
        .containsEntry("count", 1);
    assertThat(buckets.get(1)).containsEntry("lower_rwf", "1000").containsEntry("count", 1);
    assertThat(buckets.getLast()).containsEntry("lower_rwf", "50000").containsEntry("count", 1);
    assertThat(buckets).hasSize(5);
  }

  @Test
  void theNearestRankPercentileIsAnAmountOfTheWindow() {
    List<BehaviourProfiles.Txn> window = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      window.add(txn(DAY, Integer.toString(i * 100), "RWF", "USSD", "tok_a"));
    }
    assertThat(BehaviourProfiles.percentile(window, 5)).isEqualByComparingTo("100");
    assertThat(BehaviourProfiles.percentile(window, 95)).isEqualByComparingTo("1900");
  }
}
