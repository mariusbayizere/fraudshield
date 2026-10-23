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

  /**
   * HMAC-SHA256 with key PEPPER over SECRET's ASCII bytes, computed outside Java (Python's {@code
   * hmac.new(b"p" * 32, b"s" * 39 + b"WXYZ", hashlib.sha256)}), so a hash the API would not
   * recompute cannot pass.
   */
  private static final String KNOWN_HMAC =
      "f9c6e781dd9c9d1369d04802ae709a2e744bbd4565c9e405640aa2621f3a834f";

  @Test
  void hmacIsHmacSha256OfTheSecretAndNeedsLongPepper() {
    byte[] hmac = ApiKeyHasher.hmac(PEPPER, SECRET);
    assertThat(HexFormat.of().formatHex(hmac)).isEqualTo(KNOWN_HMAC);
    assertThat(ApiKeyHasher.hmac(PEPPER, SECRET.replace('W', 'V'))).isNotEqualTo(hmac);
    assertThatThrownBy(() -> ApiKeyHasher.hmac(new byte[31], SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes");
  }
}
