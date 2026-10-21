package io.github.mariusbayizere.fraudshield.auth.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyFormat;
import org.junit.jupiter.api.Test;

/** The test helper reads a key's secret as the real parser does, whatever the secret contains. */
class ApiKeysTest {

  private static final String PREFIX = "fsk_test_abcdefghijkl_";

  @Test
  void secretWithUnderscoreFifthFromLastIsReadWhole() {
    // The case that failed on main: splitting on the last "_" gave "03WE", the shown last four.
    String secret = "A".repeat(38) + "_03WE";
    String raw = PREFIX + secret;
    assertThat(secret).hasSize(ApiKeys.SECRET_LENGTH);
    assertThat(raw.substring(raw.lastIndexOf('_') + 1)).isEqualTo("03WE");

    assertThat(ApiKeys.secretOf(raw)).isEqualTo(secret);
    assertThat(ApiKeyFormat.parse(raw).orElseThrow().secret()).isEqualTo(secret);
  }

  @Test
  void generatedSecretsAreReadAsTheParserReadsThem() {
    for (int i = 0; i < 10_000; i++) {
      String raw = ApiKeyFormat.generate("test").raw();
      assertThat(ApiKeys.secretOf(raw)).isEqualTo(ApiKeyFormat.parse(raw).orElseThrow().secret());
    }
  }
}
