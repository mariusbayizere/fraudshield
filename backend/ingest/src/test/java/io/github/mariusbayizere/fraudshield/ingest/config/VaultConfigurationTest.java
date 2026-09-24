package io.github.mariusbayizere.fraudshield.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.notify.vault.InMemoryKms;
import io.github.mariusbayizere.fraudshield.notify.vault.KmsKeyProvider;
import io.github.mariusbayizere.fraudshield.notify.vault.PassphraseKeyProvider;
import io.github.mariusbayizere.fraudshield.notify.vault.TestVault;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * D-20, ADR 0069 point 10: production keeps the vault's key-encryption key in a key service. Keys
 * in configuration are for development, demos and tests, and a deployment cannot select them by
 * accident or run without either.
 */
@Tag("D-20")
@Tag("NFR-SEC-03")
class VaultConfigurationTest {

  private static final String KEY = TestVault.masterKey();

  private static FraudShieldProperties.Vault configured() {
    return new FraudShieldProperties.Vault(
        "jdbc:postgresql://vault/fraudshield_pii",
        "fs_vault",
        "secret",
        "configured",
        "m6",
        Map.of("m6", KEY),
        null,
        KEY,
        null);
  }

  private static FraudShieldProperties.Vault kms() {
    return new FraudShieldProperties.Vault(
        "jdbc:postgresql://vault/fraudshield_pii",
        "fs_vault",
        "secret",
        null,
        "kek-2026",
        null,
        List.of("kek-2025"),
        "d3JhcHBlZA==",
        "kek-2026");
  }

  @Test
  void keysFromConfigurationAreRefusedOutsideDevelopmentDemoAndTests() {
    for (String[] profiles :
        new String[][] {{}, {"prod"}, {"staging"}, {"default"}, {"production", "metrics"}}) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profiles);
      assertThatThrownBy(() -> NotificationWiring.keyProvider(configured(), environment, null))
          .as(String.join(",", profiles))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("production uses kms");
    }
    for (String profile : new String[] {"dev", "demo", "test"}) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profile);
      assertThat(NotificationWiring.keyProvider(configured(), environment, null))
          .isInstanceOf(PassphraseKeyProvider.class);
    }
  }

  @Test
  void theDefaultIsKeyServiceAndWithoutOneTheApplicationDoesNotStart() {
    assertThat(kms().keyProvider()).isEqualTo("kms");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    assertThatThrownBy(() -> NotificationWiring.keyProvider(kms(), environment, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("needs a KmsClient bean");
    InMemoryKms service = new InMemoryKms("kek-2026", "kek-2025");
    assertThat(NotificationWiring.keyProvider(kms(), new MockEnvironment(), service))
        .isInstanceOf(KmsKeyProvider.class)
        .satisfies(p -> assertThat(p.newDataKey().keyId()).isEqualTo("kek-2026"));
  }

  @Test
  void contradictoryOrIncompleteVaultSettingsAreRefused() {
    assertThatThrownBy(
            () ->
                new FraudShieldProperties.Vault(
                    "u", "fs_vault", "p", "kms", "kek", Map.of("m6", KEY), null, KEY, "kek"))
        .as("key material in configuration next to a key service")
        .hasMessageContaining("must be empty");
    assertThatThrownBy(
            () ->
                new FraudShieldProperties.Vault(
                    "u", "fs_vault", "p", "plaintext", "m6", Map.of("m6", KEY), null, KEY, null))
        .hasMessageContaining("kms or configured");
    assertThatThrownBy(
            () ->
                new FraudShieldProperties.Vault(
                    "u", "fs_vault", "p", "configured", "m6", Map.of("m6", KEY), null, null, null))
        .hasMessageContaining("index-key");
    assertThatThrownBy(
            () ->
                new FraudShieldProperties.Vault(
                    "u", "fs_vault", "p", null, "k", null, null, KEY, null))
        .hasMessageContaining("index-key-id");
    assertThat(configured().toString()).doesNotContain(KEY).contains("redacted");
  }
}
