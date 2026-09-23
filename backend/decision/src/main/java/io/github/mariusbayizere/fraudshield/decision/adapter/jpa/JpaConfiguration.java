package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned risk configuration from PostgreSQL (V4, V6), held in memory per institution and swapped
 * atomically as a whole snapshot, so a decision never mixes two versions (E.6). {@link
 * #refreshAll()} runs on a short schedule (5 s by default) and {@link #invalidate(UUID)} on a
 * {@code fs.config.changes} event, which keeps changes effective well inside E.6's 60 seconds.
 *
 * <p>The tables are read through JPA (ADR 0068, following ADR 0071): configuration is the domain
 * the rule puts in the ORM, and these reads are not on the synchronous decision path — a decision
 * reads the snapshot in memory, and the database read happens on the refresh schedule or once when
 * an institution is first seen. The entities are {@link org.hibernate.annotations.Immutable}
 * because their tables are append-only; a new configuration is a new version.
 *
 * <p>An institution with no configuration rows is decided with the SRS defaults (0.60 / 0.85,
 * release with timeout label; 5% over 15 minutes with at least 100 transactions and a 60-minute
 * clean reset), reported as version 0 and counted. A stored rule that no longer compiles is skipped
 * and counted: rules can only raise a tier, so skipping one never approves what the model would
 * have held.
 */
public final class JpaConfiguration implements ConfigurationPort {

  /** SRS default thresholds (FR-03-01..03, D-02). */
  public static final ChannelThreshold SRS_THRESHOLD =
      ChannelThreshold.of("0.60", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL);

  /** SRS and D-18 default circuit-breaker settings. */
  public static final CircuitBreakerSettings SRS_BREAKER =
      new CircuitBreakerSettings(
          new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));

  private static final Logger LOG = LoggerFactory.getLogger(JpaConfiguration.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private record Snapshot(
      Thresholds thresholds, RuleSet rules, BreakerSettings breaker, Instant loadedAt) {}

  private final TenantTransactions tenants;
  private final ThresholdRepository thresholds;
  private final RuleRepository rules;
  private final BreakerSettingsRepository breakers;
  private final Clock clock;
  private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
  private final AtomicLong defaultsUsed = new AtomicLong();
  private final AtomicLong invalidRules = new AtomicLong();

  /**
   * Creates the adapter.
   *
   * @param tenants transactions scoped to one institution
   * @param thresholds threshold repository
   * @param rules rule repository
   * @param breakers circuit-breaker settings repository
   * @param clock clock for effective times
   */
  public JpaConfiguration(
      TenantTransactions tenants,
      ThresholdRepository thresholds,
      RuleRepository rules,
      BreakerSettingsRepository breakers,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.breakers = Objects.requireNonNull(breakers, "breakers");
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
      } catch (RuntimeException e) {
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
    } catch (RuntimeException e) {
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
    Snapshot loaded = load(institutionId);
    snapshots.put(institutionId, loaded);
    return loaded;
  }

  private Snapshot load(UUID institutionId) {
    Instant now = clock.instant();
    return tenants.as(
        institutionId, () -> new Snapshot(loadThresholds(now), loadRules(), loadBreaker(now), now));
  }

  private Thresholds loadThresholds(Instant now) {
    Map<Channel, ChannelThreshold> byChannel = new EnumMap<>(Channel.class);
    long version = 0;
    for (RiskThresholdEntity row : thresholds.inForce(now)) {
      version = row.version();
      byChannel.put(
          Channel.valueOf(row.channel()),
          new ChannelThreshold(
              row.mediumThreshold(),
              row.highThreshold(),
              MediumTimeoutPolicy.valueOf(row.mediumTimeoutPolicy())));
    }
    for (Channel channel : Channel.values()) {
      if (byChannel.putIfAbsent(channel, SRS_THRESHOLD) == null) {
        defaultsUsed.incrementAndGet();
      }
    }
    return new Thresholds(version, new ChannelThresholds(byChannel));
  }

  private RuleSet loadRules() {
    List<CompiledRule> compiled = new ArrayList<>();
    long version = 0;
    for (RuleRepository.Published pair : rules.enabledWithCurrentVersion()) {
      AlertRuleEntity rule = pair.getRule();
      AlertRuleVersionEntity published = pair.getPublished();
      version = Math.max(version, rule.version());
      try {
        compiled.add(
            new CompiledRule(
                rule.id(),
                published.version(),
                TierOverride.valueOf(published.riskTierOverride()),
                RuleDefinitions.compile(
                    JSON.readTree(published.ruleExpression()), "rule_expression")));
      } catch (InvalidRuleException e) {
        invalidRules.incrementAndGet();
        LOG.error("a stored rule no longer compiles and is skipped", e);
      }
    }
    return new RuleSet(version, compiled);
  }

  private BreakerSettings loadBreaker(Instant now) {
    List<BreakerSettingsEntity> latest = breakers.inForce(now, Limit.of(1));
    if (latest.isEmpty()) {
      defaultsUsed.incrementAndGet();
      return new BreakerSettings(0, SRS_BREAKER);
    }
    BreakerSettingsEntity settings = latest.getFirst();
    return new BreakerSettings(
        settings.version(),
        new CircuitBreakerSettings(
            settings.fraudRateThreshold(),
            Duration.ofMinutes(settings.windowMinutes()),
            settings.minimumTransactions(),
            Duration.ofMinutes(settings.cleanResetMinutes())));
  }
}
