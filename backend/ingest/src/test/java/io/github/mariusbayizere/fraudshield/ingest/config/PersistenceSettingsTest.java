package io.github.mariusbayizere.fraudshield.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR 0068: Flyway owns the schema and no persistence context outlives its transaction. The
 * settings that would break either are refused at start-up rather than discovered in production.
 */
@Tag("NFR-SEC-03")
class PersistenceSettingsTest {

  private static PersistenceSettingsGuard guard() {
    return new PersistenceSettingsGuard();
  }

  @Test
  void schemaGeneratingSettingsAreRefused() {
    for (String forbidden : java.util.List.of("update", "create", "create-drop", "drop")) {
      assertThatThrownBy(
              () ->
                  guard()
                      .setEnvironment(
                          new MockEnvironment()
                              .withProperty("spring.jpa.hibernate.ddl-auto", forbidden)))
          .as(forbidden)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Flyway owns the schema");
    }
    assertThatThrownBy(
            () ->
                guard()
                    .setEnvironment(
                        new MockEnvironment()
                            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                            .withProperty(
                                "spring.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")))
        .as("the back door")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anOpenPersistenceContextInTheViewIsRefused() {
    assertThatThrownBy(
            () ->
                guard()
                    .setEnvironment(
                        new MockEnvironment()
                            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                            .withProperty("spring.jpa.open-in-view", "true")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("open-in-view");
  }

  @Test
  void theSettingsTheApplicationShipsWithPass() throws IOException {
    String configuration = Files.readString(Path.of("src", "main", "resources", "application.yml"));
    assertThat(configuration).contains("ddl-auto: validate").contains("open-in-view: false");
    assertThatCode(
            () ->
                guard()
                    .setEnvironment(
                        new MockEnvironment()
                            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                            .withProperty("spring.jpa.open-in-view", "false")))
        .doesNotThrowAnyException();
  }
}
