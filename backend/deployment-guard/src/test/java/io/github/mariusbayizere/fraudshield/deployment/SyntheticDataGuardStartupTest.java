package io.github.mariusbayizere.fraudshield.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The guard's start-up path without a database (ADR 0019 point 5): it fails closed when the
 * configured database cannot be reached, and publishes the banner flag every process serves.
 */
@Tag("D-21")
class SyntheticDataGuardStartupTest {

  @SpringBootConfiguration
  static class Empty {}

  private static SpringApplicationBuilder app(String... properties) {
    return new SpringApplicationBuilder(Empty.class)
        .web(WebApplicationType.NONE)
        .properties(properties);
  }

  @Test
  void anUnreachableDatabaseStopsStartup() {
    assertThatThrownBy(
            () ->
                app(
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/fraudshield_db",
                        "spring.datasource.username=fs_app")
                    .run()
                    .close())
        .hasStackTraceContaining("cannot check the database for synthetic demo data");
  }

  @Test
  void withoutDatabaseTheFlagFollowsTheProfiles() {
    try (ConfigurableApplicationContext dev = app().profiles("dev").run()) {
      assertThat(
              dev.getBean(SyntheticDataGuard.STATUS_BEAN, SyntheticDataStatus.class)
                  .syntheticData())
          .isTrue();
    }
    try (ConfigurableApplicationContext prod = app().profiles("prod").run()) {
      assertThat(
              prod.getBean(SyntheticDataGuard.STATUS_BEAN, SyntheticDataStatus.class)
                  .syntheticData())
          .isFalse();
    }
  }
}
