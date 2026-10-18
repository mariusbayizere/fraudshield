package io.github.mariusbayizere.fraudshield.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The Java policy against the shared cross-language vectors (FR-07-07, ADR 0014 decision 7). */
@Tag("FR-07-07")
class PasswordPolicyTest {

  private static final Path VECTORS =
      Path.of("..", "..", "contracts", "validation", "password-vectors.json");

  private static JsonNode vectors() throws IOException {
    return new ObjectMapper().readTree(VECTORS.toFile());
  }

  static Stream<JsonNode> accepted() throws IOException {
    return vectors().get("accept").valueStream();
  }

  static Stream<JsonNode> rejected() throws IOException {
    return vectors().get("reject").valueStream();
  }

  @ParameterizedTest
  @MethodSource("accepted")
  void acceptsEveryAcceptVector(JsonNode vector) {
    assertThat(PasswordPolicy.check(vector.get("input").asString()))
        .as(vector.get("note").asString())
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("rejected")
  void rejectsEveryRejectVectorWithItsReason(JsonNode vector) {
    assertThat(PasswordPolicy.check(vector.get("input").asString()))
        .as(vector.get("note").asString())
        .contains(PasswordPolicy.Rejection.valueOf(vector.get("reason").asString()));
  }

  @Test
  void limitsMatchTheVectorFile() throws IOException {
    JsonNode vectors = vectors();
    assertThat(PasswordPolicy.MIN_CODE_POINTS).isEqualTo(vectors.get("min_characters").asInt());
    assertThat(PasswordPolicy.MAX_CODE_POINTS).isEqualTo(vectors.get("max_characters").asInt());
    assertThat(PasswordPolicy.MAX_UTF8_BYTES).isEqualTo(vectors.get("max_utf8_bytes").asInt());
  }

  @Test
  void bcryptFitIsMeasuredInBytes() {
    assertThat(PasswordPolicy.fitsBcrypt("a".repeat(72))).isTrue();
    assertThat(PasswordPolicy.fitsBcrypt("a".repeat(73))).isFalse();
    assertThat(PasswordPolicy.fitsBcrypt("é".repeat(37))).isFalse();
  }
}
