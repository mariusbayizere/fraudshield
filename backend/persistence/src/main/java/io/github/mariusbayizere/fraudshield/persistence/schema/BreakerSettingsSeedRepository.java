package io.github.mariusbayizere.fraudshield.persistence.schema;

import org.springframework.data.repository.Repository;

/** Circuit-breaker settings versions, written once each (ADR 0068). */
public interface BreakerSettingsSeedRepository
    extends Repository<BreakerSettingsEntity, BreakerSettingsEntity.Key> {

  /**
   * Saves a version of the settings.
   *
   * @param settings the settings
   * @return the managed settings
   */
  BreakerSettingsEntity save(BreakerSettingsEntity settings);
}
