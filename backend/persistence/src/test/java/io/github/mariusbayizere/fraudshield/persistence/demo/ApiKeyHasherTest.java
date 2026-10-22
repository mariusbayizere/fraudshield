package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The demo seeder's API-key handling (ADR 0019 point 2): parse, never print, HMAC with a pepper.
 */
@Tag("D-21")
class ApiKeyHasherTest {

  private static final String SECRET = "s".repeat(39) + "WXYZ";
  private static final String KEY = "fsk_dev_abcdef012345_" + SECRET;
  private static final byte[] PEPPER = "p".repeat(32).getBytes(StandardCharsets.US_ASCII);

  @Test
  void parsesTheFormatAndNeverPrintsTheSecret() {
    ApiKeyHasher.ParsedKey key = ApiKeyHasher.parse(KEY);
    assertThat(key.environment()).isEqualTo("dev");
    assertThat(key.keyId()).isEqualTo("abcdef012345");
    assertThat(key.secret()).isEqualTo(SECRET);
    assertThat(key.lastFour()).isEqualTo("WXYZ");
    assertThat(key.toString()).doesNotContain(SECRET).contains("<redacted>");
  }

  @Test
  void refusesAnythingButTheFraudShieldFormat() {
    for (String bad :
        new String[] {
          "fsk_live_abcdef012345_" + SECRET,
          "fsk_dev_ABCDEF012345_" + SECRET,
          "fsk_dev_abcdef012345_" + SECRET.substring(1),
          KEY + " "
        }) {
      assertThatThrownBy(() -> ApiKeyHasher.parse(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(SECRET);
    }
  }

  @Test
  void hmacIsHmacSha256OfTheSecretAndNeedsLongPepper() {
    byte[] hmac = ApiKeyHasher.hmac(PEPPER, SECRET);
    assertThat(hmac).hasSize(32);
    assertThat(ApiKeyHasher.hmac(PEPPER, SECRET)).isEqualTo(hmac);
    assertThat(ApiKeyHasher.hmac(PEPPER, SECRET.replace('W', 'V'))).isNotEqualTo(hmac);
    assertThat(HexFormat.of().formatHex(hmac)).doesNotContain(SECRET);
    assertThatThrownBy(() -> ApiKeyHasher.hmac(new byte[31], SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes");
  }
}
