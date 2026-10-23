package io.github.mariusbayizere.fraudshield.rules.json;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.rules.dsl.InvalidRuleException;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The RuleDraft vectors shared with the contract tests (ADR 0011): the expression errors the
 * compiler reports must be exactly the vector's errors under {@code expression}.
 */
@Tag("FR-05-05")
class RuleDraftVectorsTest {

  static Stream<Arguments> vectors() throws Exception {
    Path file = Path.of("..", "..", "contracts", "validation", "request-validation-vectors.json");
    JsonNode root = new ObjectMapper().readTree(Files.readString(file));
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode vector : root.get("schemas").get("RuleDraft")) {
      if (vector.get("body").has("expression")) {
        cases.add(Arguments.of(vector.get("label").asString(), vector));
      }
    }
    assertThat(cases).hasSizeGreaterThanOrEqualTo(4);
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void expressionErrorsMatchTheVector(String label, JsonNode vector) {
    List<String> expected = new ArrayList<>();
    for (JsonNode error : vector.get("expected_errors")) {
      String field = error.get("field").asString();
      if (field.startsWith("expression")) {
        expected.add(field + " " + error.get("code").asString());
      }
    }
    List<String> actual = new ArrayList<>();
    try {
      RuleDefinitions.compile(vector.get("body").get("expression"), "expression");
    } catch (InvalidRuleException e) {
      for (RuleError error : e.errors()) {
        actual.add(error.path() + " " + error.code());
      }
    }
    assertThat(actual).as(label).containsExactlyElementsOf(expected);
  }
}
