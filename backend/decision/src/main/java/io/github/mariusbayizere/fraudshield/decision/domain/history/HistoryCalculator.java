package io.github.mariusbayizere.fraudshield.decision.domain.history;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.GeoPoint;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns what the online store read into the scoring contract's account context (FR-02-09).
 *
 * <p>Windows follow the Python online path ({@code fraudshield_ml.features.online}): every window
 * is the open interval {@code (t - W, t)}, so the scored transaction and anything stamped at or
 * after it are excluded. The 30-day mean hourly count divides by the <em>observed</em> part of the
 * prior 30 days, which needs the durable first-seen time; without it the value is NaN and the ratio
 * fails closed (PB-37), never a guess from the cache's earliest arrival.
 *
 * <p>Values with no source in FraudShield yet are absent, never invented: KYC tier, the MNO
 * SIM-swap signal, the counterparty's opening date, label-derived fraud counts, the cell fraud
 * rate, agent float and premises, and the volume ramp. The scorer applies each feature's NaN rule
 * (D-04).
 */
public final class HistoryCalculator {

  /** Longest window kept per account (amount behaviour, 90 days). */
  public static final Duration HORIZON = Duration.ofDays(90);

  private static final Duration SECOND_60 = Duration.ofSeconds(60);
  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration DAY = Duration.ofDays(1);
  private static final Duration WEEK = Duration.ofDays(7);
  private static final Duration MONTH = Duration.ofDays(30);

  private HistoryCalculator() {}

  /**
   * Computes the account context for a transaction.
   *
   * @param t the transaction about to be scored
   * @param in what the store read
   * @return the context
   */
  public static AccountHistory compute(Transaction t, HistoryInputs in) {
    Instant now = t.transactionTimestamp();
    List<Arrival> prior =
        in.account().stream()
            .filter(a -> a.at().isBefore(now) && a.at().isAfter(now.minus(HORIZON)))
            .sorted(Comparator.comparing(Arrival::at))
            .toList();
    List<Arrival> day = within(prior, now, DAY);
    List<Arrival> week = within(prior, now, WEEK);
    List<BigDecimal> amounts = prior.stream().map(Arrival::amountRwf).toList();
    Set<String> counterparties = new HashSet<>();
    for (Arrival a : day) {
      counterparties.add(a.counterparty());
    }
    Arrival last = latest(in.last(), prior, now);
    BigDecimal median = amounts.isEmpty() ? null : median(amounts);
    return new AccountHistory(
        within(prior, now, SECOND_60).size(),
        within(prior, now, HOUR).size(),
        day.size(),
        week.size(),
        sum(day),
        sum(week),
        counterparties.size(),
        meanHourlyCount30d(prior, now, in.firstSeenAt()),
        median,
        median == null ? null : mad(amounts, median),
        amounts.stream().max(BigDecimal::compareTo).orElse(null),
        prior.size(),
        last == null ? null : new GeoPoint(last.latitude(), last.longitude()),
        last == null ? null : last.at(),
        centroid(prior),
        countries(prior),
        in.openedAt() == null || in.openedAt().isAfter(now)
            ? null
            : (int) Duration.between(in.openedAt(), now).toDays(),
        null,
        null,
        last == null ? null : (int) Duration.between(last.at(), now).toDays(),
        prior.stream().noneMatch(a -> a.counterparty().equals(t.counterpartyToken())),
        null,
        distinct(in.counterpartySenders24h(), now, DAY, t.accountToken()),
        0,
        (int)
            within(prior, now, MONTH).stream()
                .filter(a -> a.counterparty().equals(t.counterpartyToken()))
                .count(),
        device(t, prior, in, now),
        agent(t, in, now),
        null,
        Math.max(0, distinct(in.deviceAccounts7d(), now, WEEK, t.accountToken())),
        null);
  }

  /**
   * Mean hourly count over the observed part of the prior 30 days, excluding the last hour (which
   * is the ratio's numerator), exactly as {@code velocity_ratio_1h_vs_30d} defines it.
   *
   * @param prior the account's arrivals before {@code now}
   * @param now the scored transaction's time
   * @param firstSeenAt durable first-seen, or null
   * @return the mean, or NaN when first-seen is unknown (fail closed, PB-37)
   */
  static double meanHourlyCount30d(List<Arrival> prior, Instant now, Instant firstSeenAt) {
    if (firstSeenAt == null) {
      return Double.NaN;
    }
    long longCount =
        prior.stream()
            .filter(a -> !a.at().isAfter(now.minus(HOUR)) && a.at().isAfter(now.minus(MONTH)))
            .count();
    double observed = Math.max(0, Duration.between(firstSeenAt, now).toMillis() / 1000.0);
    double capped = Math.min(observed, MONTH.toSeconds());
    double baselineHours = (capped - HOUR.toSeconds()) / 3600.0;
    return baselineHours > 0 ? longCount / baselineHours : 0.0;
  }

  private static AccountHistory.DeviceHistory device(
      Transaction t, List<Arrival> prior, HistoryInputs in, Instant now) {
    if (t.deviceToken() == null) {
      return null;
    }
    boolean isNew = prior.stream().noneMatch(a -> t.deviceToken().equals(a.device()));
    int changes = 0;
    String previous = null;
    for (Arrival a : within(prior, now, DAY)) {
      if (a.device() != null) {
        if (previous != null && !previous.equals(a.device())) {
          changes++;
        }
        previous = a.device();
      }
    }
    if (previous != null && !previous.equals(t.deviceToken())) {
      changes++;
    }
    Integer age =
        in.deviceFirstSeenAt() == null || in.deviceFirstSeenAt().isAfter(now)
            ? null
            : (int) Duration.between(in.deviceFirstSeenAt(), now).toDays();
    return new AccountHistory.DeviceHistory(
        isNew, distinct(in.deviceAccounts7d(), now, WEEK, null), changes, age);
  }

  private static AccountHistory.AgentHistory agent(Transaction t, HistoryInputs in, Instant now) {
    if (t.channel() != Channel.AGENT_BANKING) {
      return null;
    }
    int count =
        (int)
            in.agentCustomers1h().stream()
                .filter(e -> e.getValue().isBefore(now) && e.getValue().isAfter(now.minus(HOUR)))
                .count();
    return new AccountHistory.AgentHistory(
        null, count, distinct(in.agentCustomers1h(), now, HOUR, null), null);
  }

  /** Distinct keys in {@code (now - window, now)}, optionally not counting one key. */
  private static int distinct(
      List<Map.Entry<String, Instant>> pairs, Instant now, Duration window, String excluded) {
    Set<String> keys = new HashSet<>();
    for (Map.Entry<String, Instant> pair : pairs) {
      if (pair.getValue().isBefore(now)
          && pair.getValue().isAfter(now.minus(window))
          && !pair.getKey().equals(excluded)) {
        keys.add(pair.getKey());
      }
    }
    return keys.size();
  }

  private static List<Arrival> within(List<Arrival> prior, Instant now, Duration window) {
    Instant start = now.minus(window);
    List<Arrival> in = new ArrayList<>();
    for (Arrival a : prior) {
      if (a.at().isAfter(start)) {
        in.add(a);
      }
    }
    return in;
  }

  private static Arrival latest(Arrival durable, List<Arrival> prior, Instant now) {
    Arrival best = durable != null && durable.at().isBefore(now) ? durable : null;
    if (!prior.isEmpty() && (best == null || prior.getLast().at().isAfter(best.at()))) {
      best = prior.getLast();
    }
    return best;
  }

  private static BigDecimal sum(List<Arrival> arrivals) {
    BigDecimal total = BigDecimal.ZERO;
    for (Arrival a : arrivals) {
      total = total.add(a.amountRwf());
    }
    return total;
  }

  static BigDecimal median(List<BigDecimal> values) {
    List<BigDecimal> sorted = new ArrayList<>(values);
    sorted.sort(BigDecimal::compareTo);
    int n = sorted.size();
    return n % 2 == 1
        ? sorted.get(n / 2)
        : sorted
            .get(n / 2 - 1)
            .add(sorted.get(n / 2))
            .divide(BigDecimal.TWO, MathContext.DECIMAL64);
  }

  private static BigDecimal mad(List<BigDecimal> values, BigDecimal median) {
    List<BigDecimal> deviations = new ArrayList<>();
    for (BigDecimal value : values) {
      deviations.add(value.subtract(median).abs());
    }
    return median(deviations);
  }

  private static GeoPoint centroid(List<Arrival> prior) {
    if (prior.isEmpty()) {
      return null;
    }
    List<BigDecimal> latitudes = new ArrayList<>();
    List<BigDecimal> longitudes = new ArrayList<>();
    for (Arrival a : prior) {
      latitudes.add(BigDecimal.valueOf(a.latitude()));
      longitudes.add(BigDecimal.valueOf(a.longitude()));
    }
    return new GeoPoint(median(latitudes).doubleValue(), median(longitudes).doubleValue());
  }

  private static List<String> countries(List<Arrival> prior) {
    Set<String> countries = new TreeSet<>();
    for (Arrival a : prior) {
      if (a.counterpartyCountry() != null) {
        countries.add(a.counterpartyCountry());
      }
    }
    return List.copyOf(countries);
  }
}
