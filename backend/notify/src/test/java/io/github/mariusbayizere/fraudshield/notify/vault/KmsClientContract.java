package io.github.mariusbayizere.fraudshield.notify.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What every {@link KmsClient} must do (ADR 0069 point 3). A production binding (M9) passes this
 * suite against its service before it is wired in: extend it and supply the client and two key ids.
 */
@Tag("D-20")
@Tag("NFR-SEC-03")
public abstract class KmsClientContract {

  private static final Map<String, String> CONTEXT = Map.of("purpose", "contract-test");

  /**
   * The client under test.
   *
   * @return the client
   */
  protected abstract KmsClient client();

  /**
   * A key-encryption key the client may use.
   *
   * @return its id
   */
  protected abstract String kek();

  /**
   * A second, different key-encryption key the client may use.
   *
   * @return its id
   */
  protected abstract String otherKek();

  @Test
  void dataKeysAre256BitsAndUnwrapToThemselves() {
    KmsClient.GeneratedKey key = client().generateDataKey(kek(), CONTEXT);
    assertThat(key.plaintext()).hasSize(32);
    assertThat(key.wrapped()).isNotEqualTo(key.plaintext());
    assertThat(client().decrypt(kek(), key.wrapped(), CONTEXT)).isEqualTo(key.plaintext());
    assertThat(key.toString()).doesNotContain(java.util.Arrays.toString(key.plaintext()));
  }

  @Test
  void twoDataKeysAreDifferent() {
    assertThat(client().generateDataKey(kek(), CONTEXT).plaintext())
        .isNotEqualTo(client().generateDataKey(kek(), CONTEXT).plaintext());
  }

  @Test
  void theWrongContextKeyOrWrappingDoesNotUnwrap() {
    byte[] wrapped = client().generateDataKey(kek(), CONTEXT).wrapped();
    assertThatThrownBy(() -> client().decrypt(kek(), wrapped, Map.of("purpose", "other")))
        .isInstanceOf(VaultException.class);
    assertThatThrownBy(() -> client().decrypt(otherKek(), wrapped, CONTEXT))
        .isInstanceOf(VaultException.class);
    byte[] tampered = wrapped.clone();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(() -> client().decrypt(kek(), tampered, CONTEXT))
        .isInstanceOf(VaultException.class);
    assertThatThrownBy(() -> client().decrypt(kek(), new byte[0], CONTEXT))
        .isInstanceOf(VaultException.class);
  }

  @Test
  void anUnknownKeyIsRefused() {
    assertThatThrownBy(() -> client().generateDataKey("no-such-key", CONTEXT))
        .isInstanceOf(VaultException.class);
  }
}
