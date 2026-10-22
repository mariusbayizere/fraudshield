package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The seeding settings carry credentials and must never print them (ADR 0019 point 2). */
@Tag("D-21")
class DemoSeedPropertiesTest {

  @Test
  void neverPrintsPasswordsTheKeyOrThePepper() {
    DemoSeedProperties.Passwords passwords =
        new DemoSeedProperties.Passwords("pw-analyst", "pw-senior", "pw-ra", "pw-rb", "pw-admin");
    DemoSeedProperties properties =
        new DemoSeedProperties(true, passwords, "fsk_dev_key-secret", "a1b2c3");
    for (String printed : new String[] {properties.toString(), passwords.toString()}) {
      assertThat(printed)
          .contains("<redacted>")
          .doesNotContain("pw-", "fsk_dev_key-secret", "a1b2c3");
    }
    assertThat(properties.toString()).contains("enabled=true");
  }
}
