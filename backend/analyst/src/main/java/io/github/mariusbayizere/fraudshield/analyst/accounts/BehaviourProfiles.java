package io.github.mariusbayizere.fraudshield.analyst.accounts;

import io.github.mariusbayizere.fraudshield.analyst.support.CountryPacks;
import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The behavioural fingerprint of FR-04-07, computed from the account's transactions in the 90 days
 * before the compared one. Every rule is the contract's {@code BehaviourProfile} description (ADR
 * 0110 points 5 and 6); this class holds no data access, so each rule is tested directly.
 */
public final class BehaviourProfiles {

  /** Below this many transactions in the window, nothing is called normal or anomalous. */
  static final int MINIMUM_HISTORY = 10;

  /** Bucket edges in RWF, each bucket half-open [lower, upper). */
  private static final long[] EDGES = {
    0L,
    1_000L,
    5_000L,
    10_000L,
    50_000L,
    100_000L,
    500_000L,
    1_000_000L,
    5_000_000L,
    10_000_000L,
    50_000_000L,
    100_000_000L,
    500_000_000L,
    1_000_000_000L,
    10_000_000_000L,
    100_000_000_000L,
    1_000_000_000_000L,
    99_999_999_999_999L
  };

  /**
   * One transaction as the profile reads it.
   *
   * @param at when it happened
   * @param amountRwf its amount in RWF
   * @param currency its currency
   * @param channel its channel
   * @param counterparty its counterparty token
   * @param device its device token, or null
   * @param counterpartyCountry its counterparty's country, or null
   */
  public record Txn(
      Instant at,
      BigDecimal amountRwf,
      String currency,
      String channel,
      String counterparty,
      String device,
      String counterpartyCountry) {}

  private final CountryPacks packs;

  /**
   * Creates the computation.
   *
   * @param packs the country packs, for local time
   */
  public BehaviourProfiles(CountryPacks packs) {
    this.packs = Objects.requireNonNull(packs, "packs");
  }

  /**
   * The profile.
   *
   * @param compared the compared transaction
   * @param window the account's transactions in the 90 days before it, oldest first
   * @param institutionCountry the institution's country, the local-time fallback
   * @return the contract's {@code BehaviourProfile}
   */
  public Map<String, Object> profile(Txn compared, List<Txn> window, String institutionCountry) {
    int[] hours = new int[24];
    for (Txn t : window) {
      hours[packs.localHour(t.at(), t.currency(), institutionCountry)]++;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("window_days", 90);
    List<Integer> hourly = new ArrayList<>(24);
    for (int count : hours) {
      hourly.add(count);
    }
    view.put("hourly_counts", hourly);
    view.put("amount_histogram", histogram(window));
    view.put("channel_mix", channelMix(window));
    int hour = packs.localHour(compared.at(), compared.currency(), institutionCountry);
    view.put("comparison", comparison(compared, hour, hours, window));
    return view;
  }

  private static List<Map<String, Object>> histogram(List<Txn> window) {
    if (window.isEmpty()) {
      return List.of();
    }
    int first = Integer.MAX_VALUE;
    int last = -1;
    int[] counts = new int[EDGES.length - 1];
    for (Txn t : window) {
      int bucket = bucket(t.amountRwf());
      counts[bucket]++;
      first = Math.min(first, bucket);
      last = Math.max(last, bucket);
    }
    List<Map<String, Object>> buckets = new ArrayList<>();
    for (int b = first; b <= last; b++) {
      Map<String, Object> bucket = new LinkedHashMap<>();
      bucket.put("lower_rwf", Long.toString(EDGES[b]));
      bucket.put("upper_rwf", Long.toString(EDGES[b + 1]));
      bucket.put("count", counts[b]);
      buckets.add(bucket);
    }
    return buckets;
  }

  private static int bucket(BigDecimal amount) {
    for (int b = 0; b < EDGES.length - 2; b++) {
      if (amount.compareTo(BigDecimal.valueOf(EDGES[b + 1])) < 0) {
        return b;
      }
    }
    return EDGES.length - 2;
  }

  private static List<Map<String, Object>> channelMix(List<Txn> window) {
    Map<String, Integer> counts = counts(window, Txn::channel);
    List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
    ranked.sort(
        Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
            .thenComparing(Map.Entry.comparingByKey()));
    List<Map<String, Object>> mix = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : ranked) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("channel", entry.getKey());
      item.put("count", entry.getValue());
      mix.add(item);
    }
    return mix;
  }

  private static List<Map<String, Object>> comparison(
      Txn compared, int hour, int[] hours, List<Txn> window) {
    boolean enough = window.size() >= MINIMUM_HISTORY;
    List<Map<String, Object>> items = new ArrayList<>(6);

    int[] range = enough ? hourRange(hours, window.size()) : null;
    boolean outsideHours = range != null && !inRange(hour, range);
    items.add(item("local_hour", hour, range == null ? null : hours(range), outsideHours));

    BigDecimal low = enough ? percentile(window, 5) : null;
    BigDecimal high = enough ? percentile(window, 95) : null;
    boolean outsideAmounts =
        enough
            && (compared.amountRwf().compareTo(low) < 0
                || compared.amountRwf().compareTo(high) > 0);
    items.add(
        item(
            "amount_rwf",
            Views.decimal(compared.amountRwf()),
            enough ? Views.decimal(low) + "-" + Views.decimal(high) : null,
            outsideAmounts));

    Map<String, Integer> channels = counts(window, Txn::channel);
    int sameChannel = channels.getOrDefault(compared.channel(), 0);
    items.add(
        item(
            "channel",
            compared.channel(),
            enough ? mostFrequent(channels) : null,
            enough && sameChannel * 20 < window.size()));

    long priorCounterparty =
        window.stream().filter(t -> compared.counterparty().equals(t.counterparty())).count();
    items.add(
        item(
            "counterparty_prior_transactions",
            priorCounterparty,
            null,
            enough && priorCounterparty == 0));

    if (compared.device() == null) {
      items.add(item("device_prior_transactions", null, null, false));
    } else {
      long priorDevice = window.stream().filter(t -> compared.device().equals(t.device())).count();
      items.add(item("device_prior_transactions", priorDevice, null, enough && priorDevice == 0));
    }

    if (compared.counterpartyCountry() == null) {
      items.add(item("counterparty_country", null, null, false));
    } else {
      Map<String, Integer> countries = new TreeMap<>();
      for (Txn t : window) {
        if (t.counterpartyCountry() != null) {
          countries.merge(t.counterpartyCountry(), 1, Integer::sum);
        }
      }
      boolean unseen = !countries.containsKey(compared.counterpartyCountry());
      items.add(
          item(
              "counterparty_country",
              compared.counterpartyCountry(),
              enough && !countries.isEmpty() ? mostFrequent(countries) : null,
              enough && unseen));
    }
    return items;
  }

  /**
   * The shortest range of hours, possibly wrapping midnight, that holds at least 90% of the
   * window's transactions; of equal ranges, the one starting earliest after 00:00.
   *
   * @return {start, end}, both inclusive
   */
  static int[] hourRange(int[] hours, int total) {
    for (int length = 1; length <= 24; length++) {
      for (int start = 0; start < 24; start++) {
        int count = 0;
        for (int i = 0; i < length; i++) {
          count += hours[(start + i) % 24];
        }
        if (count * 10 >= total * 9) {
          return new int[] {start, (start + length - 1) % 24};
        }
      }
    }
    return new int[] {0, 23};
  }

  private static boolean inRange(int hour, int[] range) {
    return range[0] <= range[1]
        ? hour >= range[0] && hour <= range[1]
        : hour >= range[0] || hour <= range[1];
  }

  private static String hours(int[] range) {
    return String.format(Locale.ROOT, "%02d-%02d", range[0], range[1]);
  }

  /** Nearest-rank percentile of the window's amounts. */
  static BigDecimal percentile(List<Txn> window, int percent) {
    List<BigDecimal> amounts = new ArrayList<>();
    for (Txn t : window) {
      amounts.add(t.amountRwf());
    }
    amounts.sort(Comparator.naturalOrder());
    int rank = (int) Math.ceil(percent / 100.0 * amounts.size());
    return amounts.get(Math.max(rank, 1) - 1);
  }

  private static Map<String, Integer> counts(List<Txn> window, Function<Txn, String> key) {
    Map<String, Integer> counts = new TreeMap<>();
    for (Txn t : window) {
      counts.merge(key.apply(t), 1, Integer::sum);
    }
    return counts;
  }

  /** The most frequent key; ties go to the first in order, the counts' map being sorted. */
  private static String mostFrequent(Map<String, Integer> counts) {
    String best = null;
    int most = -1;
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > most) {
        best = entry.getKey();
        most = entry.getValue();
      }
    }
    return best;
  }

  private static Map<String, Object> item(
      String attribute, Object thisTransaction, Object normal, boolean anomalous) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("attribute", attribute);
    item.put("this_transaction", thisTransaction);
    item.put("normal", normal);
    item.put("anomalous", anomalous);
    return item;
  }
}
