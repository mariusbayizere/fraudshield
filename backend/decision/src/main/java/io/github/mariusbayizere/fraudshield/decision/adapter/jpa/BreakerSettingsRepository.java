package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import io.github.mariusbayizere.fraudshield.persistence.schema.BreakerSettingsEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The MCC circuit-breaker settings in force (ADR 0068). */
public interface BreakerSettingsRepository
    extends Repository<BreakerSettingsEntity, BreakerSettingsEntity.Key> {

  /**
   * The latest settings effective at a time, newest first.
   *
   * @param now the time the settings must be effective at
   * @param limit how many rows (one)
   * @return the rows
   */
  @Query(
      "select b from BreakerSettingsEntity b where b.effectiveAt <= :now order by b.version desc")
  List<BreakerSettingsEntity> inForce(@Param("now") Instant now, Limit limit);
}
