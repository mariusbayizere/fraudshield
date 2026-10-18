package io.github.mariusbayizere.fraudshield.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CryptoTest {

  private static final byte[] KEY = Crypto.randomBytes(32);
  private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

  @Test
  void randomTokensAreUrlSafeAndDistinct() {
    String token = Crypto.randomToken(32);
    assertThat(token).matches("^[A-Za-z0-9_-]{43}$").isNotEqualTo(Crypto.randomToken(32));
    assertThat(Crypto.fromBase64url(token)).hasSize(32);
  }

  @Test
  void hmacRequiresFullLengthKeys() {
    assertThatThrownBy(() -> Crypto.hmacSha256(new byte[16], new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Crypto.hmacSha256(KEY, "a".getBytes(), "b".getBytes()))
        .isEqualTo(Crypto.hmacSha256(KEY, "ab".getBytes()));
  }

  @Test
  @Tag("FR-07-06")
  void signedTokensVerifyOnceForTheirPurposeAndState() {
    SignedToken codec = new SignedToken(KEY);
    UUID user = UUID.randomUUID();
    String token =
        codec.issue(SignedToken.Purpose.ACCOUNT_UNLOCK, user, NOW.plusSeconds(60), "state-1");
    assertThat(token).matches("^[A-Za-z0-9_-]{22,128}$");
    SignedToken.Claims claims =
        codec.verify(token, SignedToken.Purpose.ACCOUNT_UNLOCK, NOW).orElseThrow();
    assertThat(claims.userId()).isEqualTo(user);
    assertThat(claims.boundTo("state-1")).isTrue();
    assertThat(claims.boundTo("state-2")).as("state changed by using the token").isFalse();
    assertThat(codec.verify(token, SignedToken.Purpose.PASSWORD_RESET, NOW))
        .as("other purpose")
        .isEmpty();
    assertThat(codec.verify(token, SignedToken.Purpose.ACCOUNT_UNLOCK, NOW.plusSeconds(60)))
        .as("expired")
        .isEmpty();
    assertThat(
            new SignedToken(Crypto.randomBytes(32))
                .verify(token, SignedToken.Purpose.ACCOUNT_UNLOCK, NOW))
        .as("other key")
        .isEmpty();
    // A character in the middle: the last character of unpadded base64url can carry padding bits,
    // and changing only those decodes to the same bytes, so the "tampered" token verified whenever
    // the random token ended that way (seen in CI on 6303efe, 2026-10-17).
    int middle = token.length() / 2;
    char original = token.charAt(middle);
    String tampered =
        token.substring(0, middle) + (original == 'A' ? 'B' : 'A') + token.substring(middle + 1);
    assertThat(codec.verify(tampered, SignedToken.Purpose.ACCOUNT_UNLOCK, NOW)).isEmpty();
    assertThat(codec.verify("not*base64", SignedToken.Purpose.ACCOUNT_UNLOCK, NOW)).isEmpty();
    assertThat(codec.verify("c2hvcnQ", SignedToken.Purpose.ACCOUNT_UNLOCK, NOW)).isEmpty();
    assertThatThrownBy(() -> new SignedToken(new byte[8]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void secretBoxAuthenticatesCiphertextAndAssociatedData() {
    SecretBox box = new SecretBox("box-1", Crypto.randomBytes(32));
    byte[] ciphertext = box.seal("whsec_test_secret", "api-key:abc");
    assertThat(box.open(ciphertext, "api-key:abc")).isEqualTo("whsec_test_secret");
    assertThat(box.seal("whsec_test_secret", "api-key:abc"))
        .as("fresh nonce")
        .isNotEqualTo(ciphertext);
    assertThatThrownBy(() -> box.open(ciphertext, "api-key:other"))
        .isInstanceOf(IllegalArgumentException.class);
    ciphertext[ciphertext.length - 1] ^= 1;
    assertThatThrownBy(() -> box.open(ciphertext, "api-key:abc"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> box.open(new byte[4], "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretBox("k", new byte[16]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(box.keyId()).isEqualTo("box-1");
  }
}
