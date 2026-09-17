package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.util.Arrays;
import org.springframework.core.env.Environment;

/**
 * Decides the {@code synthetic_data} flag of {@code GET /api/v1/environment}, which makes the UI
 * show "SYNTHETIC DATA — NOT FOR PRODUCTION" (D-21, ADR 0019).
 *
 * <p>True whenever demo seeding is enabled or a dev or demo profile is active: those deployments
 * hold only synthetic data. Because {@link DemoSeedGuard} refuses to start with demo seeding under
 * any other profile, a deployment that seeds demo data always shows the banner.
 */
public final class SyntheticDataFlag {

  private SyntheticDataFlag() {}

  /**
   * Whether the deployment runs on synthetic data.
   *
   * @param environment the application environment
   * @return true when demo seeding is enabled or a dev or demo profile is active
   */
  public static boolean isSyntheticData(Environment environment) {
    return DemoSeedGuard.isEnabled(environment)
        || Arrays.stream(environment.getActiveProfiles())
            .anyMatch(DemoSeedGuard.ALLOWED_PROFILES::contains);
  }
}
