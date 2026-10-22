package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.deployment.DemoSeedGuard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** The database tool's own seeding switches (ADR 0019, D-21); the guard itself is tested apart. */
@Tag("D-21")
class DemoSeedConfigurationTest {

  /** An empty application that reads this module's application.yml. */
  @SpringBootConfiguration
  static class EmptyApplication {}

  @Test
  void applicationYamlLeavesSeedingDisabledByDefault() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(EmptyApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("prod")
            .run("--spring.datasource.url=")) {
      assertThat(context.getEnvironment().getProperty(DemoSeedGuard.ENABLED_PROPERTY))
          .as("the module's application.yml sets it, and to false")
          .isEqualTo("false");
      assertThat(DemoSeedGuard.isEnabled(context.getEnvironment())).isFalse();
    }
  }

  @Test
  void seederBeanFactoryChecksAgain() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty(DemoSeedGuard.ENABLED_PROPERTY, "true");
    assertThatThrownBy(
            () ->
                new DemoSeedConfiguration()
                    .demoDataSeeder(
                        environment, null, null, null, null, null, null, null, null, null))
        .isInstanceOf(IllegalStateException.class);
  }
}
