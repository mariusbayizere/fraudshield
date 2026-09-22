package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The thresholds of the version in force (ADR 0068).
 *
 * <p>Read-only and tenant-scoped by row-level security, which applies because the institution is
 * set on the same connection and transaction by {@link TenantTransactions}. One statement returns
 * every channel, so the load cannot grow into N + 1 queries.
 */
public interface ThresholdRepository
    extends Repository<RiskThresholdEntity, RiskThresholdEntity.Key> {

  /**
   * Every channel's thresholds in the latest version effective at a time.
   *
   * @param now the time the configuration must be effective at
   * @return the rows, empty when the institution has no configuration
   */
  @Query(
      """
      select t from RiskThresholdEntity t where t.version =
        (select max(v.version) from RiskThresholdVersionEntity v where v.effectiveAt <= :now)
      """)
  List<RiskThresholdEntity> inForce(@Param("now") Instant now);
}
