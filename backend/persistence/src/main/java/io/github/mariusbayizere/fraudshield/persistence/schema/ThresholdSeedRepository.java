package io.github.mariusbayizere.fraudshield.persistence.schema;

import org.springframework.data.repository.Repository;

/** Per-channel thresholds, written once each (ADR 0068). */
public interface ThresholdSeedRepository
    extends Repository<RiskThresholdEntity, RiskThresholdEntity.Key> {

  /**
   * Saves one channel's thresholds.
   *
   * @param threshold the row
   * @return the managed row
   */
  RiskThresholdEntity save(RiskThresholdEntity threshold);
}
