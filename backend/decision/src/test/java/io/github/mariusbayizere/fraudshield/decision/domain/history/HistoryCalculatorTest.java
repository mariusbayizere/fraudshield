package io.github.mariusbayizere.fraudshield.decision.domain.history;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.ACCOUNT;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.COUNTERPARTY;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** FR-02-09: the account context, hand-computed. */
@Tag("FR-02-09")
@Tag("FR-01-04")
@Tag("D-04")
class HistoryCalculatorTest {

  private static final String OTHER = "tok_OtherCounterpartyAaaaBbbb1";

  private static Arrival arrival(Duration ago, String amount, String counterparty, String device) {
    return new Arrival(
        UUID.randomUUID(),
        NOW.minus(ago),
        new BigDecimal(amount),
        counterparty,
        -1.95,
        30.06,
        "RW",
        device);
  }

  private static HistoryInputs inputs(List<Arrival> arrivals, Instant firstSeen) {
    return new HistoryInputs(
        arrivals, null, firstSeen, null, List.of(), List.of(), null, List.of());
  }

  private static final Transaction MOBILE = Fixtures.transaction("1000");

  @Test
  void windowsAreOpenAtBothEndsAndExcludeTheScoredTransaction() {
    List<Arrival> arrivals =
        List.of(
            arrival(Duration.ZERO, "5", COUNTERPARTY, null), // at t: excluded
            arrival(Duration.ofSeconds(-5), "5", COUNTERPARTY, null), // after t: excluded
            arrival(Duration.ofSeconds(59), "100", COUNTERPARTY, null), // 60 s, 1 h, 24 h, 7 d
            arrival(Duration.ofSeconds(60), "200", OTHER, null), // exactly 60 s: not in 60 s
            arrival(Duration.ofMinutes(59), "300", OTHER, null), // 1 h
            arrival(Duration.ofHours(1), "400", OTHER, null), // exactly 1 h: not in 1 h
            arrival(Duration.ofHours(23), "500", OTHER, null), // 24 h
            arrival(Duration.ofDays(6), "600", OTHER, null), // 7 d
            arrival(Duration.ofDays(8), "700", OTHER, null), // 90 d only
            arrival(Duration.ofDays(90), "800", OTHER, null)); // exactly 90 d: out
    AccountHistory h =
        HistoryCalculator.compute(MOBILE, inputs(arrivals, NOW.minus(Duration.ofDays(200))));
    assertThat(h.txCount60s()).isEqualTo(1);
    assertThat(h.txCount1h()).isEqualTo(3);
    assertThat(h.txCount24h()).isEqualTo(5);
    assertThat(h.txCount7d()).isEqualTo(6);
    assertThat(h.amountSum24hRwf()).isEqualByComparingTo("1500");
    assertThat(h.amountSum7dRwf()).isEqualByComparingTo("2100");
    assertThat(h.uniqueCounterparties24h()).isEqualTo(2);
    assertThat(h.historyCount90d()).isEqualTo(7);
    assertThat(h.amountMax90dRwf()).isEqualByComparingTo("700");
    // Median of 100..700 is 400; absolute deviations 300,200,100,0,100,200,300 -> MAD 200.
    assertThat(h.amountMedian90dRwf()).isEqualByComparingTo("400");
    assertThat(h.amountMad90dRwf()).isEqualByComparingTo("200");
    assertThat(h.lastTransactionAt()).isEqualTo(NOW.minusSeconds(59));
    assertThat(h.daysSincePreviousActivity()).isZero();
    assertThat(h.counterpartyNewForAccount()).isFalse();
    assertThat(h.txCountToCounterparty30d()).isEqualTo(1);
    assertThat(h.countriesSeen()).containsExactly("RW");
  }

  @Test
  @Tag("FR-02-09")
  void meanHourlyCountDividesByObservedHistoryAndFailsClosedWithoutFirstSeen() {
    List<Arrival> arrivals =
        List.of(
            arrival(Duration.ofMinutes(30), "1", COUNTERPARTY, null), // last hour: numerator only
            arrival(Duration.ofHours(1), "1", COUNTERPARTY, null), // exactly 1 h: in the baseline
            arrival(Duration.ofHours(5), "1", COUNTERPARTY, null),
            arrival(Duration.ofDays(29), "1", COUNTERPARTY, null),
            arrival(Duration.ofDays(31), "1", COUNTERPARTY, null)); // before 30 d: out
    // First seen 11 hours ago (so only the three recent arrivals exist): the baseline is 10
    // hours holding 2 arrivals -> 0.2 per hour.
    AccountHistory young =
        HistoryCalculator.compute(
            MOBILE, inputs(arrivals.subList(0, 3), NOW.minus(Duration.ofHours(11))));
    assertThat(young.meanHourlyCount30d()).isCloseTo(0.2, within(1e-12));
    // First seen long ago: capped at 30 days -> 3 arrivals over 719 hours.
    AccountHistory old =
        HistoryCalculator.compute(MOBILE, inputs(arrivals, NOW.minus(Duration.ofDays(400))));
    assertThat(old.meanHourlyCount30d()).isCloseTo(3 / 719.0, within(1e-12));
    // PB-37: without a durable first-seen the value is NaN, never inferred from arrivals.
    assertThat(HistoryCalculator.compute(MOBILE, inputs(arrivals, null)).meanHourlyCount30d())
        .isNaN();
    // Seen for less than an hour: no baseline at all.
    assertThat(
            HistoryCalculator.meanHourlyCount30d(arrivals, NOW, NOW.minus(Duration.ofMinutes(10))))
        .isZero();
  }

  @Test
  void emptyHistoriesHaveNoAmountsNoLocationAndNewCounterparties() {
    AccountHistory h = HistoryCalculator.compute(MOBILE, inputs(List.of(), null));
    assertThat(h.txCount7d()).isZero();
    assertThat(h.amountMedian90dRwf()).isNull();
    assertThat(h.amountMad90dRwf()).isNull();
    assertThat(h.lastLocation()).isNull();
    assertThat(h.homeCentroid90d()).isNull();
    assertThat(h.counterpartyNewForAccount()).isTrue();
    assertThat(h.kycTier()).isNull();
    assertThat(h.daysSinceSimSwap())
        .as("MNO signal unavailable, D-25 treats it as unsafe")
        .isNull();
  }

  @Test
  @Tag("FR-01-04")
  void ussdHasNoDeviceStateAndOnlyAgentBankingHasAgentState() {
    Transaction ussd = Fixtures.transaction(UUID.randomUUID(), ACCOUNT, "100", Channel.USSD);
    assertThat(HistoryCalculator.compute(ussd, inputs(List.of(), null)).device()).isNull();
    assertThat(HistoryCalculator.compute(ussd, inputs(List.of(), null)).agent()).isNull();
    Transaction agentTx =
        Fixtures.transaction(UUID.randomUUID(), ACCOUNT, "100", Channel.AGENT_BANKING);
    HistoryInputs withAgent =
        new HistoryInputs(
            List.of(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                Map.entry("tok_CustomerOneAaaaBbbbCccc01", NOW.minusSeconds(100)),
                Map.entry("tok_CustomerOneAaaaBbbbCccc01", NOW.minusSeconds(200)),
                Map.entry("tok_CustomerTwoAaaaBbbbCccc01", NOW.minusSeconds(300)),
                Map.entry("tok_CustomerTwoAaaaBbbbCccc01", NOW.minus(Duration.ofHours(1)))));
    AccountHistory.AgentHistory agent = HistoryCalculator.compute(agentTx, withAgent).agent();
    assertThat(agent.cashoutCount1h()).isEqualTo(3);
    assertThat(agent.uniqueCustomers1h()).isEqualTo(2);
    assertThat(agent.floatUtilisationRatio()).isNull();
  }

  @Test
  void deviceNoveltyChangesAndSharing() {
    String device = "tok_DeviceHhhhIiiiJjjjKkkk01";
    List<Arrival> arrivals =
        List.of(
            arrival(Duration.ofHours(3), "1", COUNTERPARTY, "tok_OldDeviceAaaaBbbbCccc01"),
            arrival(Duration.ofHours(2), "1", COUNTERPARTY, "tok_OldDeviceAaaaBbbbCccc02"));
    HistoryInputs in =
        new HistoryInputs(
            arrivals,
            null,
            null,
            null,
            List.of(),
            List.of(
                Map.entry(ACCOUNT, NOW.minus(Duration.ofDays(1))),
                Map.entry("tok_SomeoneElseAaaaBbbbCccc1", NOW.minus(Duration.ofDays(2))),
                Map.entry("tok_LongAgoAaaaBbbbCcccDddd1", NOW.minus(Duration.ofDays(8)))),
            NOW.minus(Duration.ofDays(3)),
            List.of());
    AccountHistory h = HistoryCalculator.compute(Fixtures.transaction("1"), in);
    assertThat(h.device().newForAccount()).isTrue();
    assertThat(h.device().deviceChanges24h()).isEqualTo(2);
    assertThat(h.device().accountsPerDevice7d()).isEqualTo(2);
    assertThat(h.device().deviceAgeDays()).isEqualTo(3);
    assertThat(h.accountsSharingDeviceOrPhone()).isEqualTo(1);
  }

  @Test
  @Tag("FR-02-09")
  void theDurableLastTransactionOutlivesTheHorizonAndOpeningDateGivesAge() {
    Arrival old = arrival(Duration.ofDays(200), "10", COUNTERPARTY, null);
    HistoryInputs in =
        new HistoryInputs(
            List.of(),
            old,
            NOW.minus(Duration.ofDays(300)),
            NOW.minus(Duration.ofDays(400)),
            List.of(
                Map.entry("tok_SenderAaaaBbbbCcccDddd01", NOW.minusSeconds(10)),
                Map.entry(ACCOUNT, NOW.minusSeconds(20))),
            List.of(),
            null,
            List.of());
    AccountHistory h = HistoryCalculator.compute(MOBILE, in);
    assertThat(h.lastTransactionAt()).isEqualTo(old.at());
    assertThat(h.daysSincePreviousActivity()).isEqualTo(200);
    assertThat(h.accountAgeDays()).isEqualTo(400);
    assertThat(h.counterpartyUniqueSenders24h()).as("other senders only").isEqualTo(1);
  }
}
