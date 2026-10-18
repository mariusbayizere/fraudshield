package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The synthetic-data banner shows whenever demo data can be present (D-21, ADR 0019). */
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
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true, "demo"), false)).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true, "dev"), false)).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(true), false)).isTrue();
  }

  @Test
  void devAndDemoProfilesShowTheBannerWithoutSeeding() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "dev"), false)).isTrue();
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "demo"), false)).isTrue();
  }

  @Test
  void seededDatabaseShowsTheBannerWhateverTheProcessSettings() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "prod"), true)).isTrue();
  }

  @Test
  void productionWithoutSeedingShowsNoBanner() {
    assertThat(SyntheticDataFlag.isSyntheticData(environment(false, "prod"), false)).isFalse();
    assertThat(SyntheticDataFlag.isSyntheticData(new MockEnvironment(), false)).isFalse();
  }

  @Test
  void seededDatabaseIsRefusedOutsideDevAndDemo() {
    for (String[] profiles :
        new String[][] {{"prod"}, {"staging"}, {}, {"default"}, {"dev", "prod"}}) {
      assertThatThrownBy(() -> SyntheticDataFlag.checkProfiles(environment(false, profiles), true))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("synthetic demo data");
    }
    assertThatCode(() -> SyntheticDataFlag.checkProfiles(environment(false, "demo"), true))
        .doesNotThrowAnyException();
    assertThatCode(() -> SyntheticDataFlag.checkProfiles(environment(false, "prod"), false))
        .doesNotThrowAnyException();
  }
}
