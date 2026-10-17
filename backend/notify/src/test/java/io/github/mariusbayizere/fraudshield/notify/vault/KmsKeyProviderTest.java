package io.github.mariusbayizere.fraudshield.notify.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D-20: with a key management service, the key-encryption key never enters the process. */
@Tag("D-20")
@Tag("NFR-SEC-03")
class KmsKeyProviderTest {

  private final InMemoryKms kms = new InMemoryKms("kek-2026", "kek-2025", "kek-unlisted");

  @Test
  void dataKeysComeFromTheServiceAndUnwrapUnderTheirOwnKeyId() {
    KmsKeyProvider provider = new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2026", "kek-2025"));
    KeyProvider.DataKey key = provider.newDataKey();
    assertThat(key.keyId()).isEqualTo("kek-2026");
    assertThat(key.key().getEncoded()).hasSize(32);
    SecretKey unwrapped = provider.unwrap("kek-2026", key.wrapped());
    assertThat(unwrapped.getEncoded()).isEqualTo(key.key().getEncoded());
  }

  @Test
  void rowsUnderRetiredKeysStillReadAfterRotation() {
    KeyProvider.DataKey old = new KmsKeyProvider(kms, "kek-2025", Set.of("kek-2025")).newDataKey();
    KmsKeyProvider rotated = new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2026", "kek-2025"));
    assertThat(rotated.unwrap("kek-2025", old.wrapped()).getEncoded())
        .isEqualTo(old.key().getEncoded());
    assertThat(rotated.newDataKey().keyId()).isEqualTo("kek-2026");
  }

  @Test
  void rowsNamingKeysThisDeploymentDoesNotUseAreRefusedBeforeTheServiceIsAsked() {
    KmsKeyProvider provider = new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2026"));
    byte[] wrapped =
        new KmsKeyProvider(kms, "kek-unlisted", Set.of("kek-unlisted")).newDataKey().wrapped();
    int before = kms.calls.get();
    assertThatThrownBy(() -> provider.unwrap("kek-unlisted", wrapped))
        .isInstanceOf(VaultException.class)
        .hasMessageContaining("no master key with id kek-unlisted")
        .as("a key this instance has not been given yet is retried, never dead-lettered")
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
    assertThat(kms.calls.get()).isEqualTo(before);
  }

  @Test
  void wrappingsMovedToAnotherKeyIdOrTamperedWithDoNotUnwrap() {
    KmsKeyProvider provider = new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2026", "kek-2025"));
    byte[] wrapped = provider.newDataKey().wrapped();
    assertThatThrownBy(() -> provider.unwrap("kek-2025", wrapped))
        .isInstanceOf(VaultException.class);
    wrapped[wrapped.length - 1] ^= 1;
    assertThatThrownBy(() -> provider.unwrap("kek-2026", wrapped))
        .isInstanceOf(VaultException.class);
  }

  @Test
  void anUnreachableServiceFailsTheCallAndNeverFallsBack() {
    KmsKeyProvider provider = new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2026"));
    byte[] wrapped = provider.newDataKey().wrapped();
    kms.down = true;
    assertThatThrownBy(provider::newDataKey)
        .as("a service that is down may come back: not permanent")
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
    assertThatThrownBy(() -> provider.unwrap("kek-2026", wrapped))
        .isInstanceOf(VaultException.class);
  }

  @Test
  void theConfigurationIsCheckedWhenTheProviderIsBuilt() {
    assertThatThrownBy(() -> new KmsKeyProvider(kms, "kek-2026", Set.of("kek-2025")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KmsKeyProvider(kms, "k".repeat(65), Set.of("k".repeat(65))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
