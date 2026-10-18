package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("D-19")
class ApiKeyFormatTest {

  @Test
  void generatedKeysMatchTheContractPatternAndParseBack() {
    for (int i = 0; i < 200; i++) {
      ApiKeyFormat.ParsedKey key = ApiKeyFormat.generate("test");
      assertThat(key.raw()).matches("^fsk_(dev|test|stg|prod)_[a-z0-9]{12}_[A-Za-z0-9_-]{43}$");
      assertThat(key.lastFour()).matches("^[A-Za-z0-9]{4}$");
      assertThat(ApiKeyFormat.parse(key.raw())).contains(key);
      assertThat(key.toString()).doesNotContain(key.secret());
    }
  }

  @Test
  void webhookSecretsMatchTheContractPattern() {
    assertThat(ApiKeyFormat.webhookSecret("prod"))
        .matches("^whsec_(dev|test|stg|prod)_[A-Za-z0-9]{32,64}$");
  }

  @Test
  void rejectsMalformedKeysAndEnvironments() {
    assertThat(ApiKeyFormat.parse(null)).isEmpty();
    assertThat(ApiKeyFormat.parse("fsk_live_abcdefghijkl_" + "a".repeat(43))).isEmpty();
    assertThat(ApiKeyFormat.parse("fsk_test_ABCDEFGHIJKL_" + "a".repeat(43))).isEmpty();
    assertThat(ApiKeyFormat.parse("fsk_test_abcdefghijkl_" + "a".repeat(42))).isEmpty();
    assertThatThrownBy(() -> ApiKeyFormat.generate("live"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ApiKeyScope.parse("ingest:write")).contains(ApiKeyScope.INGEST_WRITE);
    assertThat(ApiKeyScope.parse("admin")).isEmpty();
  }
}
