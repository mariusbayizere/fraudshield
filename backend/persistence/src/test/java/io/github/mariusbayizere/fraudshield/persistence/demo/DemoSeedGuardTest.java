package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Demo seeding must refuse to run outside the dev and demo profiles (ADR 0019, D-21). */
@Tag("D-21")
class DemoSeedGuardTest {

  /** An empty application: the guard is registered through spring.factories, not by this class. */
  @SpringBootConfiguration
  static class EmptyApplication {}

  private static ConfigurableApplicationContext start(String profiles, boolean seeding) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(EmptyApplication.class).web(WebApplicationType.NONE);
    if (!profiles.isEmpty()) {
      builder.profiles(profiles.split(","));
    }
    // A command-line argument, not builder.properties(): default properties rank below
    // application.yml, which disables seeding, so the guard would never see the value.
    return builder.run(
        "--" + DemoSeedGuard.ENABLED_PROPERTY + "=" + seeding, "--spring.datasource.url=");
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "production", "staging", "default", "dev,prod", "demo,staging"})
  void applicationStartupFailsWhenDemoSeedingIsEnabledUnderAnotherProfile(String profiles) {
    assertThatThrownBy(() -> start(profiles, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Refusing to start: demo seeding")
        .hasMessageContaining("ADR 0019");
  }

  @Test
  void applicationStartupFailsWhenDemoSeedingIsEnabledWithoutAnyProfile() {
    assertThatThrownBy(() -> start("", true)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void applicationYamlLeavesSeedingDisabledByDefault() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(EmptyApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("prod")
            .run("--spring.datasource.url=")) {
      assertThat(DemoSeedGuard.isEnabled(context.getEnvironment())).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "demo", "dev,demo"})
  void devAndDemoProfilesMaySeed(String profiles) {
    try (ConfigurableApplicationContext context = start(profiles, true)) {
      assertThat(DemoSeedGuard.isEnabled(context.getEnvironment())).isTrue();
    }
  }

  @Test
  void productionStartsNormallyWhenSeedingIsDisabled() {
    assertThatCode(() -> start("prod", false).close()).doesNotThrowAnyException();
  }

  @Test
  void seederBeanFactoryChecksAgain() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty(DemoSeedGuard.ENABLED_PROPERTY, "true");
    assertThatThrownBy(
            () -> new DemoSeedConfiguration().demoDataSeeder(environment, null, null, null))
        .isInstanceOf(IllegalStateException.class);
  }
}
