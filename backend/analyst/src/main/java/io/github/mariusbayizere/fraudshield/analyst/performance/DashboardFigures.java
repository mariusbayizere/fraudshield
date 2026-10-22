package io.github.mariusbayizere.fraudshield.analyst.performance;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The institution's header figures (ADR 0117 point 4), the same for every caller: computed for
 * {@code getDashboardStats} and, once per institution, for the real-time channel's figures
 * destination (ADR 0115 point 6.3).
 */
public final class DashboardFigures {

  private final TenantTransactions tenants;
  private final PerformanceQueries queries;
  private final Presence presence;
  private final Clock clock;

  /**
   * Creates the figures.
   *
   * @param tenants tenant transactions
   * @param queries the figures' SQL
   * @param presence who has the console open
   * @param clock the clock
   */
  public DashboardFigures(
      TenantTransactions tenants, PerformanceQueries queries, Presence presence, Clock clock) {
    this.tenants = Objects.requireNonNull(tenants);
    this.queries = Objects.requireNonNull(queries);
    this.presence = Objects.requireNonNull(presence);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * The institution's {@code DashboardStats} now.
   *
   * @param institutionId the institution
   * @return the contract's {@code DashboardStats}
   */
  public Map<String, Object> stats(UUID institutionId) {
    final Instant now = clock.instant();
    Map<String, Object> stats =
        tenants.inTenant(
            institutionId,
            () -> {
              InstitutionDay today = InstitutionDay.of(queries.zone(), now);
              Map<String, Object> figures = queries.dashboard(today);
              day(figures, today);
              return figures;
            });
    // Outside the transaction: Redis is not the database, and its answer must not hold a
    // connection.
    stats.put("analysts_online", presence.online(institutionId));
    return stats;
  }

  /**
   * Names the institution day a figure counts (ADR 0117 point 1).
   *
   * @param figures the body
   * @param day the day
   */
  static void day(Map<String, Object> figures, InstitutionDay day) {
    figures.put("day_starts_at", Views.timestamp(day.start()));
    figures.put("utc_offset", day.utcOffset());
    figures.put("time_zone", day.zone().getId());
  }
}
