package io.github.mariusbayizere.fraudshield.persistence.demo;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deployment's synthetic-data state, checked once when the application context starts (ADR
 * 0019). Creating it fails for a profile other than dev or demo against a database that holds demo
 * data, so such a service never finishes starting. The API serves {@link #syntheticData()} as
 * {@code GET /api/v1/environment}.
 */
public final class SyntheticDataStatus {

  private final boolean syntheticData;

  /**
   * Reads the database marker and applies the profile check.
   *
   * @param environment the application environment
   * @param jdbc template on the application's database
   * @throws IllegalStateException for a production-like profile against a seeded database
   */
  public SyntheticDataStatus(Environment environment, JdbcTemplate jdbc) {
    boolean database = SyntheticDataFlag.databaseHasSyntheticData(jdbc);
    SyntheticDataFlag.checkProfiles(environment, database);
    this.syntheticData = SyntheticDataFlag.isSyntheticData(environment, database);
  }

  /**
   * Whether the UI must show the synthetic-data banner.
   *
   * @return the {@code synthetic_data} flag
   */
  public boolean syntheticData() {
    return syntheticData;
  }
}
