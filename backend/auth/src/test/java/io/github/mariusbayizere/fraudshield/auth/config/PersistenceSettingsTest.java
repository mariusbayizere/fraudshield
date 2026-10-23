package io.github.mariusbayizere.fraudshield.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** The persistence defaults and the guard that refuses unsafe settings (ADR 0071). */
class PersistenceSettingsTest {

  @Test
  void defaultsValidateSchemaAndDisableOpenInView() {
    MockEnvironment environment = new MockEnvironment();
    new PersistenceDefaults().postProcessEnvironment(environment, new SpringApplication());
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema"))
        .isEqualTo("fraudshield");
    new PersistenceSettingsGuard(environment);
  }

  @Test
  void explicitConfigurationWinsOverTheDefaults() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "none");
    new PersistenceDefaults().postProcessEnvironment(environment, new SpringApplication());
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
    new PersistenceSettingsGuard(environment);
  }

  @Test
  void refusesSchemaGenerationAndOpenInView() {
    for (String ddl : new String[] {"update", "create", "create-drop", "CREATE"}) {
      assertThatThrownBy(
              () ->
                  new PersistenceSettingsGuard(
                      new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", ddl)))
          .as(ddl)
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                new PersistenceSettingsGuard(
                    new MockEnvironment()
                        .withProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "update")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new PersistenceSettingsGuard(
                    new MockEnvironment().withProperty("spring.jpa.open-in-view", "true")))
        .isInstanceOf(IllegalStateException.class);
  }
}
