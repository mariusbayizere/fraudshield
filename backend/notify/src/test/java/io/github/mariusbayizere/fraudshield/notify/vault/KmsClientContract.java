package io.github.mariusbayizere.fraudshield.notify.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What every {@link KmsClient} must do (ADR 0069 point 3). A production binding (M9) passes this
 * suite against its service before it is wired in: extend it and supply the client, two key ids and
 * the same binding pointed at a service it cannot reach. It checks which failures are permanent (a
 * wrapping that does not verify) and which are not (an unknown key, an unreachable service),
 * because the notification consumer dead-letters the first and retries the second.
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

  /**
   * The same binding pointed at a service it cannot reach (a closed port, a blackholed endpoint).
   *
   * @return a client whose every call fails as an unreachable service does
   */
  protected abstract KmsClient unreachableClient();

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
    assertThatThrownBy(() -> client().decrypt(kek(), tampered, CONTEXT))
        .as("no retry makes a tampered wrapping verify")
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isTrue());
  }

  @Test
  void anUnknownKeyIsRefusedButNotPermanently() {
    // During a rolling rotation an instance may be asked for a key the service is about to have
    // or this deployment is about to be given: that work is retried, never dead-lettered (ADR
    // 0069).
    byte[] wrapped = client().generateDataKey(kek(), CONTEXT).wrapped();
    assertThatThrownBy(() -> client().generateDataKey("no-such-key", CONTEXT))
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
    assertThatThrownBy(() -> client().decrypt("no-such-key", wrapped, CONTEXT))
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
  }

  @Test
  void anUnreachableServiceIsNeverPermanent() {
    // A throttle, a network blip or an outage passes; dead-lettering on it would drop customers'
    // block SMS (the fourth review, 2026-09-23).
    byte[] wrapped = client().generateDataKey(kek(), CONTEXT).wrapped();
    assertThatThrownBy(() -> unreachableClient().generateDataKey(kek(), CONTEXT))
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
    assertThatThrownBy(() -> unreachableClient().decrypt(kek(), wrapped, CONTEXT))
        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.permanent()).isFalse());
  }
}
