package io.github.mariusbayizere.fraudshield.persistence.schema;

import org.springframework.data.repository.Repository;

/** Threshold versions, written once each (ADR 0068). */
public interface ThresholdVersionRepository
    extends Repository<RiskThresholdVersionEntity, RiskThresholdVersionEntity.Key> {

  /**
   * Saves a version.
   *
   * @param version the version
   * @return the managed version
   */
  RiskThresholdVersionEntity save(RiskThresholdVersionEntity version);
}
