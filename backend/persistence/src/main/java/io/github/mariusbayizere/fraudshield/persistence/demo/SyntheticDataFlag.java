package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.util.Arrays;
import org.springframework.core.env.Environment;

/**
 * Decides the {@code synthetic_data} flag of {@code GET /api/v1/environment}, which makes the UI
 * show "SYNTHETIC DATA — NOT FOR PRODUCTION" (D-21, ADR 0019), and refuses production profiles
 * against a database that holds demo data.
 *
 * <p>The database records demo seeding ({@code institutions.synthetic}), because seeding runs in a
 * separate one-shot process: a service started later against the same database must still know
 * (review MAJOR-3).
 */
public final class SyntheticDataFlag {

  private SyntheticDataFlag() {}

  /**
   * Whether the deployment runs on synthetic data.
   *
   * @param environment the application environment
   * @param databaseHasSyntheticData whether the database marker is set
   * @return true when the database holds demo data, demo seeding is enabled, or a dev or demo
   *     profile is active
   */
  public static boolean isSyntheticData(Environment environment, boolean databaseHasSyntheticData) {
    return databaseHasSyntheticData
        || DemoSeedGuard.isEnabled(environment)
        || Arrays.stream(environment.getActiveProfiles())
            .anyMatch(DemoSeedGuard.ALLOWED_PROFILES::contains);
  }

  /**
   * Fails when the database holds demo data and the active profiles are not only dev or demo.
   *
   * @param environment the application environment
   * @param databaseHasSyntheticData whether the database marker is set
   * @throws IllegalStateException for a production-like profile against a seeded database
   */
  public static void checkProfiles(Environment environment, boolean databaseHasSyntheticData) {
    if (databaseHasSyntheticData && !DemoSeedGuard.onlyDevOrDemoProfiles(environment)) {
      throw new IllegalStateException(
          "Refusing to start: the database holds synthetic demo data (institutions.synthetic),"
              + " which is allowed only when every active profile is dev or demo, but the active"
              + " profiles are "
              + DemoSeedGuard.activeProfiles(environment)
              + " (ADR 0019)");
    }
  }
}
