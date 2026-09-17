package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The synthetic-data banner shows whenever demo seeding is active (D-21, ADR 0019). */
@Tag("D-21")
class SyntheticDataFlagTest {

  private static MockEnvironment environment(boolean seeding, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    environment.setProperty(DemoSeedGuard.ENABLED_PROPERTY, Boolean.toString(seeding));
    return environment;
  }

  @Test
  void demoSeedingAlwaysShowsTheBanner() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true, "demo"))).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true, "dev"))).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true))).isTrue();
  }

  @Test
  void devAndDemoProfilesShowTheBannerWithoutSeeding() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "dev"))).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "demo"))).isTrue();
  }

  @Test
  void productionWithoutSeedingShowsNoBanner() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "prod"))).isFalse();
    assertThat(SyntheticDataFlag.isSyntheticData(new MockEnvironment())).isFalse();
  }
}
