package io.github.mariusbayizere.fraudshield.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Person-name rule checked against the vectors shared with TypeScript (ADR 0013). */
@Tag("FR-07-02")
@Tag("UX-REG-01")
@Tag("UX-REG-02")
class PersonNameTest {

  private static final Path VECTORS =
      Path.of("..", "..", "contracts", "validation", "person-name-vectors.json");

  private static JsonNode vectors() throws IOException {
    return new ObjectMapper().readTree(VECTORS.toFile());
  }

  static Stream<JsonNode> accepted() throws IOException {
    return vectors().get("accept").valueStream();
  }

  static Stream<JsonNode> rejected() throws IOException {
    return vectors().get("reject").valueStream();
  }

  @ParameterizedTest(name = "accepts {0}")
  @MethodSource("accepted")
  void acceptsRealNamesAndNormalisesToNfc(JsonNode vector) {
    String input = vector.get("input").asString();
    PersonName name = PersonName.parse(input);
    assertThat(PersonName.check(input)).isEmpty();
    assertThat(name.value()).isEqualTo(vector.get("normalised").asString());
    assertThat(Normalizer.isNormalized(name.value(), Normalizer.Form.NFC)).isTrue();
  }

  @ParameterizedTest(name = "rejects {0}")
  @MethodSource("rejected")
  void rejectsWithTheExpectedReason(JsonNode vector) {
    String input = vector.get("input").asString();
    PersonName.Rejection expected = PersonName.Rejection.valueOf(vector.get("reason").asString());
    assertThat(PersonName.check(input)).contains(expected);
    assertThatThrownBy(() -> PersonName.parse(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(expected.name());
  }

  @Test
  void lengthIsCountedInCodePointsNotUtf16Units() {
    String supplementaryLetter = new String(Character.toChars(0x1D400));
    assertThat(PersonName.check(supplementaryLetter)).contains(PersonName.Rejection.LENGTH);
    assertThat(PersonName.check(supplementaryLetter + supplementaryLetter)).isEmpty();
  }

  @Test
  void canonicalConstructorRejectsUnnormalisedValues() {
    String decomposed = Normalizer.normalize("Ngũgĩ", Normalizer.Form.NFD);
    assertThatThrownBy(() -> new PersonName(decomposed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NFC");
    assertThat(PersonName.check(null)).contains(PersonName.Rejection.CHARACTERS);
  }
}
