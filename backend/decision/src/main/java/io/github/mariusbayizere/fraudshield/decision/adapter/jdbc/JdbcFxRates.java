package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.decision.application.port.FxRatePort;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * FX rates from V61 {@code fx_rates}, cached per (currency, date) for ten minutes so the hot path
 * reads memory. RWF is always 1 and never queried.
 */
public final class JdbcFxRates implements FxRatePort {

  /** How long a looked-up rate is reused. */
  public static final Duration CACHE_TTL = Duration.ofMinutes(10);

  private record Key(CurrencyCode currency, LocalDate date) {}

  private record Cached(Optional<BigDecimal> rate, Instant loadedAt) {}

  private static final String SELECT_FX_RATES =
      """
      SELECT rwf_per_unit FROM fx_rates WHERE currency = ? AND rate_date <= ? ORDER BY rate_date
      DESC, recorded_at DESC LIMIT 1
      """;

  private final DataSource dataSource;
  private final Clock clock;
  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

  /**
   * Creates the adapter.
   *
   * @param dataSource connections as {@code fs_app}
   * @param clock clock for cache expiry
   */
  public JdbcFxRates(DataSource dataSource, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<BigDecimal> rwfPerUnit(CurrencyCode currency, LocalDate date) {
    if (currency == CurrencyCode.RWF) {
      return Optional.of(BigDecimal.ONE);
    }
    Key key = new Key(currency, date);
    Instant now = clock.instant();
    Cached cached = cache.get(key);
    if (cached == null || cached.loadedAt().plus(CACHE_TTL).isBefore(now)) {
      cached = new Cached(load(key), now);
      cache.put(key, cached);
    }
    return cached.rate();
  }

  private Optional<BigDecimal> load(Key key) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(SELECT_FX_RATES)) {
      s.setString(1, key.currency().name());
      s.setObject(2, key.date());
      try (ResultSet row = s.executeQuery()) {
        return row.next() ? Optional.of(row.getBigDecimal(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("FX rates are unavailable", e);
    }
  }
}
