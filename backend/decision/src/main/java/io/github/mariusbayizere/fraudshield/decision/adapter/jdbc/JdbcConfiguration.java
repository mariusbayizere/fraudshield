package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import io.github.mariusbayizere.fraudshield.common.config.ChannelThresholds;
import io.github.mariusbayizere.fraudshield.common.config.CircuitBreakerSettings;
import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.application.port.ConfigurationPort;
import io.github.mariusbayizere.fraudshield.rules.dsl.CompiledRule;
import io.github.mariusbayizere.fraudshield.rules.dsl.InvalidRuleException;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import io.github.mariusbayizere.fraudshield.rules.dsl.TierOverride;
import io.github.mariusbayizere.fraudshield.rules.json.RuleDefinitions;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned risk configuration from PostgreSQL (V4, V6), held in memory per institution and swapped
 * atomically as a whole snapshot, so a decision never mixes two versions (E.6). {@link
 * #refreshAll()} runs on a short schedule (5 s by default) and {@link #invalidate(UUID)} on a
 * {@code fs.config.changes} event, which keeps changes effective well inside E.6's 60 seconds.
 *
 * <p>An institution with no configuration rows is decided with the SRS defaults (0.60 / 0.85,
 * release with timeout label; 5% over 15 minutes with at least 100 transactions and a 60-minute
 * clean reset), reported as version 0 and counted. A stored rule that no longer compiles is skipped
 * and counted: rules can only raise a tier, so skipping one never approves what the model would
 * have held.
 */
public final class JdbcConfiguration implements ConfigurationPort {

  /** SRS default thresholds (FR-03-01..03, D-02). */
  public static final ChannelThreshold SRS_THRESHOLD =
      ChannelThreshold.of("0.60", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL);

  /** SRS and D-18 default circuit-breaker settings. */
  public static final CircuitBreakerSettings SRS_BREAKER =
      new CircuitBreakerSettings(
          new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));

  private static final Logger LOG = LoggerFactory.getLogger(JdbcConfiguration.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private record Snapshot(
      Thresholds thresholds, RuleSet rules, BreakerSettings breaker, Instant loadedAt) {}

  private static final String SELECT_RISK_THRESHOLDS =
      """
      SELECT t.version, t.channel, t.medium_threshold, t.high_threshold, t.medium_timeout_policy
      FROM risk_thresholds t WHERE t.version = ( SELECT max(version) FROM
      risk_threshold_versions WHERE effective_at <= ?)
      """;

  private static final String SELECT_ALERT_RULES =
      """
      SELECT r.id, v.version, v.rule_expression::text, v.risk_tier_override, r.version FROM
      alert_rules r JOIN alert_rule_versions v ON v.rule_id = r.id AND v.version =
      r.current_version WHERE r.state = 'ENABLED' ORDER BY r.id
      """;

  private static final String SELECT_MCC_CIRCUIT_BREAKER_SETTINGS_VERSIONS =
      """
      SELECT version, fraud_rate_threshold, window_minutes, minimum_transactions,
      clean_reset_minutes FROM mcc_circuit_breaker_settings_versions WHERE effective_at <= ?
      ORDER BY version DESC LIMIT 1
      """;

  private final DataSource dataSource;
  private final Clock clock;
  private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
  private final AtomicLong defaultsUsed = new AtomicLong();
  private final AtomicLong invalidRules = new AtomicLong();

  /**
   * Creates the adapter.
   *
   * @param dataSource connections as {@code fs_app}
   * @param clock clock for effective times
   */
  public JdbcConfiguration(DataSource dataSource, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Thresholds thresholds(UUID institutionId) {
    return snapshot(institutionId).thresholds();
  }

  @Override
  public RuleSet rules(UUID institutionId) {
    return snapshot(institutionId).rules();
  }

  @Override
  public BreakerSettings breakerSettings(UUID institutionId) {
    return snapshot(institutionId).breaker();
  }

  /**
   * Reloads every cached institution; failures keep the previous snapshot.
   *
   * @return institutions reloaded
   */
  public int refreshAll() {
    int reloaded = 0;
    for (UUID institution : List.copyOf(snapshots.keySet())) {
      try {
        snapshots.put(institution, load(institution));
        reloaded++;
      } catch (SQLException e) {
        LOG.warn("configuration refresh failed; keeping the previous snapshot", e);
      }
    }
    return reloaded;
  }

  /**
   * Reloads one institution now, after a {@code fs.config.changes} event.
   *
   * @param institutionId institution
   */
  public void invalidate(UUID institutionId) {
    try {
      snapshots.put(institutionId, load(institutionId));
    } catch (SQLException e) {
      LOG.warn("configuration reload failed; keeping the previous snapshot", e);
    }
  }

  /**
   * Institutions decided with SRS defaults because they have no configuration rows.
   *
   * @return count of loads that used defaults
   */
  public long defaultsUsed() {
    return defaultsUsed.get();
  }

  /**
   * Stored rules skipped because they did not compile.
   *
   * @return count
   */
  public long invalidRules() {
    return invalidRules.get();
  }

  private Snapshot snapshot(UUID institutionId) {
    Snapshot snapshot = snapshots.get(institutionId);
    if (snapshot != null) {
      return snapshot;
    }
    try {
      Snapshot loaded = load(institutionId);
      snapshots.put(institutionId, loaded);
      return loaded;
    } catch (SQLException e) {
      throw new IllegalStateException("risk configuration is unavailable", e);
    }
  }

  private Snapshot load(UUID institutionId) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        Instant now = clock.instant();
        Snapshot snapshot =
            new Snapshot(loadThresholds(c, now), loadRules(c), loadBreaker(c, now), now);
        c.commit();
        return snapshot;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private Thresholds loadThresholds(Connection c, Instant now) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_RISK_THRESHOLDS)) {
      s.setObject(1, Tenant.utc(now));
      Map<Channel, ChannelThreshold> byChannel = new EnumMap<>(Channel.class);
      long version = 0;
      try (ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          version = rows.getLong(1);
          byChannel.put(
              Channel.valueOf(rows.getString(2)),
              new ChannelThreshold(
                  rows.getBigDecimal(3),
                  rows.getBigDecimal(4),
                  MediumTimeoutPolicy.valueOf(rows.getString(5))));
        }
      }
      for (Channel channel : Channel.values()) {
        if (byChannel.putIfAbsent(channel, SRS_THRESHOLD) == null) {
          defaultsUsed.incrementAndGet();
        }
      }
      return new Thresholds(version, new ChannelThresholds(byChannel));
    }
  }

  private RuleSet loadRules(Connection c) throws SQLException {
    List<CompiledRule> compiled = new ArrayList<>();
    long version = 0;
    try (PreparedStatement s = c.prepareStatement(SELECT_ALERT_RULES);
        ResultSet rows = s.executeQuery()) {
      while (rows.next()) {
        version = Math.max(version, rows.getLong(5));
        try {
          compiled.add(
              new CompiledRule(
                  rows.getObject(1, UUID.class),
                  rows.getInt(2),
                  TierOverride.valueOf(rows.getString(4)),
                  RuleDefinitions.compile(JSON.readTree(rows.getString(3)), "rule_expression")));
        } catch (InvalidRuleException e) {
          invalidRules.incrementAndGet();
          LOG.error("a stored rule no longer compiles and is skipped", e);
        }
      }
    }
    return new RuleSet(version, compiled);
  }

  private BreakerSettings loadBreaker(Connection c, Instant now) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_MCC_CIRCUIT_BREAKER_SETTINGS_VERSIONS)) {
      s.setObject(1, Tenant.utc(now));
      try (ResultSet rows = s.executeQuery()) {
        if (!rows.next()) {
          defaultsUsed.incrementAndGet();
          return new BreakerSettings(0, SRS_BREAKER);
        }
        return new BreakerSettings(
            rows.getLong(1),
            new CircuitBreakerSettings(
                rows.getBigDecimal(2),
                Duration.ofMinutes(rows.getInt(3)),
                rows.getInt(4),
                Duration.ofMinutes(rows.getInt(5))));
      }
    }
  }
}
