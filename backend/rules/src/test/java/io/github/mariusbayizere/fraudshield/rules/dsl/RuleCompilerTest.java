package io.github.mariusbayizere.fraudshield.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.rules.json.RuleDefinitions;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/** Every operator, including null semantics (E.6). */
@Tag("FR-05-05")
class RuleCompilerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static RulePredicate rule(String json) {
    return RuleDefinitions.compile(JSON.readTree(json), "expression");
  }

  private static RuleSubject subject(Object... pairs) {
    Map<String, FieldValue> values = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      Object value = pairs[i + 1];
      values.put(
          (String) pairs[i],
          switch (value) {
            case null -> FieldValue.MISSING;
            case String text -> FieldValue.category(text);
            case Double number -> FieldValue.of(number);
            case Integer number -> FieldValue.of(BigDecimal.valueOf(number));
            default -> throw new IllegalArgumentException(value.toString());
          });
    }
    return field -> values.getOrDefault(field, FieldValue.MISSING);
  }

  private static List<RuleError> errorsOf(String json) {
    try {
      rule(json);
    } catch (InvalidRuleException e) {
      return e.errors();
    }
    throw new AssertionError("expected an invalid rule: " + json);
  }

  @ParameterizedTest(name = "{0} {1} {2} on {3} -> {4}")
  @CsvSource({
    "tx_count_1h, eq, 3, 3, TRUE",
    "tx_count_1h, eq, 3, 4, FALSE",
    "tx_count_1h, ne, 3, 4, TRUE",
    "tx_count_1h, ne, 3, 3, FALSE",
    "tx_count_1h, gt, 3, 4, TRUE",
    "tx_count_1h, gt, 3, 3, FALSE",
    "tx_count_1h, gte, 3, 3, TRUE",
    "tx_count_1h, gte, 3, 2, FALSE",
    "tx_count_1h, lt, 3, 2, TRUE",
    "tx_count_1h, lt, 3, 3, FALSE",
    "tx_count_1h, lte, 3, 3, TRUE",
    "tx_count_1h, lte, 3, 4, FALSE"
  })
  void numericComparisons(String field, String op, int value, int actual, Truth expected) {
    RulePredicate predicate =
        rule("{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + value + "}");
    assertThat(predicate.evaluate(subject(field, actual))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} on a missing value is UNKNOWN")
  @CsvSource({"eq", "ne", "gt", "gte", "lt", "lte"})
  void comparisonsWithMissingValuesAreUnknownAndNeverFire(String op) {
    RulePredicate predicate =
        rule("{\"field\":\"device_age_days\",\"op\":\"" + op + "\",\"value\":1}");
    assertThat(predicate.evaluate(subject("device_age_days", null))).isEqualTo(Truth.UNKNOWN);
    assertThat(predicate.matches(subject("device_age_days", Double.NaN))).isFalse();
  }

  @Test
  void nullTestsSeeMissingAndNanAsNull() {
    RulePredicate isNull = rule("{\"field\":\"device_age_days\",\"op\":\"is_null\"}");
    RulePredicate isNotNull =
        rule("{\"field\":\"device_age_days\",\"op\":\"is_not_null\",\"value\":null}");
    assertThat(isNull.evaluate(subject("device_age_days", null))).isEqualTo(Truth.TRUE);
    assertThat(isNull.evaluate(subject("device_age_days", Double.NaN))).isEqualTo(Truth.TRUE);
    assertThat(isNull.evaluate(subject("device_age_days", 2.0))).isEqualTo(Truth.FALSE);
    assertThat(isNotNull.evaluate(subject("device_age_days", 2.0))).isEqualTo(Truth.TRUE);
    assertThat(isNotNull.evaluate(subject("device_age_days", null))).isEqualTo(Truth.FALSE);
  }

  @Test
  void membershipOnNumbersAndCategories() {
    RulePredicate in =
        rule(
            "{\"field\":\"merchant_category_code\",\"op\":\"in\","
                + "\"value\":[\"6051\",\"4829\"]}");
    RulePredicate notIn = rule("{\"field\":\"kyc_tier\",\"op\":\"not_in\",\"value\":[2,3.0]}");
    assertThat(in.evaluate(subject("merchant_category_code", "4829"))).isEqualTo(Truth.TRUE);
    assertThat(in.evaluate(subject("merchant_category_code", "5411"))).isEqualTo(Truth.FALSE);
    assertThat(in.evaluate(subject())).isEqualTo(Truth.UNKNOWN);
    assertThat(notIn.evaluate(subject("kyc_tier", 1))).isEqualTo(Truth.TRUE);
    assertThat(notIn.evaluate(subject("kyc_tier", 3))).isEqualTo(Truth.FALSE);
    assertThat(notIn.evaluate(subject("kyc_tier", null))).isEqualTo(Truth.UNKNOWN);
  }

  @Test
  void betweenIsInclusiveAtBothEnds() {
    RulePredicate between =
        rule("{\"field\":\"amount_rwf\",\"op\":\"between\",\"value\":[1000,5000.5]}");
    assertThat(between.evaluate(subject("amount_rwf", 1000))).isEqualTo(Truth.TRUE);
    assertThat(between.evaluate(subject("amount_rwf", 5000.5))).isEqualTo(Truth.TRUE);
    assertThat(between.evaluate(subject("amount_rwf", 999.9999))).isEqualTo(Truth.FALSE);
    assertThat(between.evaluate(subject("amount_rwf", 5000.5001))).isEqualTo(Truth.FALSE);
    assertThat(between.evaluate(subject())).isEqualTo(Truth.UNKNOWN);
  }

  @Test
  void categoryEqualityIsExact() {
    RulePredicate channel = rule("{\"field\":\"channel\",\"op\":\"eq\",\"value\":\"USSD\"}");
    assertThat(channel.evaluate(subject("channel", "USSD"))).isEqualTo(Truth.TRUE);
    assertThat(channel.evaluate(subject("channel", "ussd"))).isEqualTo(Truth.FALSE);
    RulePredicate notChannel = rule("{\"field\":\"channel\",\"op\":\"ne\",\"value\":\"USSD\"}");
    assertThat(notChannel.evaluate(subject("channel", "CARD"))).isEqualTo(Truth.TRUE);
    assertThat(notChannel.evaluate(subject("channel", 3))).isEqualTo(Truth.UNKNOWN);
  }

  @Test
  void booleanOperandsAreOneAndZero() {
    RulePredicate night = rule("{\"field\":\"is_local_night\",\"op\":\"eq\",\"value\":true}");
    assertThat(night.evaluate(subject("is_local_night", 1))).isEqualTo(Truth.TRUE);
    assertThat(night.evaluate(subject("is_local_night", 0))).isEqualTo(Truth.FALSE);
  }

  @Test
  void logicalNodesCombineThreeValuedResults() {
    String rule =
        "{\"all\":[{\"field\":\"is_local_night\",\"op\":\"eq\",\"value\":1},"
            + "{\"any\":[{\"field\":\"agent_cashout_count_1h\",\"op\":\"gte\",\"value\":3},"
            + "{\"not\":{\"field\":\"device_age_days\",\"op\":\"gt\",\"value\":30}}]}]}";
    RulePredicate predicate = rule(rule);
    assertThat(predicate.evaluate(subject("is_local_night", 1, "agent_cashout_count_1h", 3)))
        .isEqualTo(Truth.TRUE);
    assertThat(
            predicate.evaluate(
                subject("is_local_night", 1, "agent_cashout_count_1h", 1, "device_age_days", 2)))
        .isEqualTo(Truth.TRUE);
    assertThat(
            predicate.evaluate(
                subject("is_local_night", 1, "agent_cashout_count_1h", 1, "device_age_days", 40)))
        .isEqualTo(Truth.FALSE);
    // Agent count 1 and no device: the not(...) is UNKNOWN, so the rule is UNKNOWN and does not
    // fire.
    assertThat(predicate.evaluate(subject("is_local_night", 1, "agent_cashout_count_1h", 1)))
        .isEqualTo(Truth.UNKNOWN);
    assertThat(predicate.evaluate(subject("is_local_night", 0))).isEqualTo(Truth.FALSE);
  }

  @Test
  void unknownFieldsAndOperatorsAreRejectedWithPaths() {
    assertThat(
            errorsOf(
                "{\"all\":[{\"field\":\"customer_name\",\"op\":\"eq\",\"value\":\"x\"},"
                    + "{\"field\":\"tx_count_1h\",\"op\":\"regex\",\"value\":\"x\"}]}"))
        .extracting(RuleError::path, RuleError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("expression.all[0].field", "unsupported_value"),
            org.assertj.core.groups.Tuple.tuple("expression.all[1].op", "unsupported_value"));
  }

  @Test
  void operandShapesAreCheckedAgainstTheFieldType() {
    assertThat(errorsOf("{\"field\":\"channel\",\"op\":\"gt\",\"value\":1}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"eq\",\"value\":\"3\"}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":\"channel\",\"op\":\"eq\",\"value\":3}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"is_null\",\"value\":3}"))
        .extracting(RuleError::code)
        .containsExactly("unsupported_value");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"in\",\"value\":3}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"in\",\"value\":[]}"))
        .extracting(RuleError::code)
        .containsExactly("item_count_out_of_range");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"in\",\"value\":[1,\"a\",null]}"))
        .extracting(RuleError::path)
        .containsExactly("expression.value[1]", "expression.value[2]");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"between\",\"value\":[5,1]}"))
        .extracting(RuleError::code)
        .containsExactly("out_of_range");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"between\",\"value\":[5]}"))
        .extracting(RuleError::code)
        .containsExactly("item_count_out_of_range");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"between\",\"value\":[1,\"x\"]}"))
        .extracting(RuleError::path)
        .containsExactly("expression.value[1]");
  }

  @Test
  void structuralErrorsUseTheApiCodes() {
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"value\":3}"))
        .extracting(RuleError::path, RuleError::code)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("expression.op", "required"));
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"eq\",\"value\":3,\"code\":\"x\"}"))
        .extracting(RuleError::code)
        .containsExactly("unknown_field");
    assertThat(errorsOf("{\"all\":[],\"any\":[]}"))
        .extracting(RuleError::code)
        .containsExactly("unknown_field");
    assertThat(errorsOf("{\"all\":{}}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("[1]")).extracting(RuleError::code).containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":1,\"op\":\"eq\"}"))
        .extracting(RuleError::code)
        .containsExactly("type_mismatch");
    assertThat(errorsOf("{\"field\":\"tx_count_1h\",\"op\":\"eq\",\"value\":{\"a\":1}}"))
        .extracting(RuleError::code)
        .contains("type_mismatch");
  }

  @Test
  void depthAndSizeAreBounded() {
    String deep = "{\"field\":\"tx_count_1h\",\"op\":\"gt\",\"value\":1}";
    for (int i = 0; i < RuleCompiler.MAX_DEPTH; i++) {
      deep = "{\"not\":" + deep + "}";
    }
    assertThat(errorsOf(deep)).extracting(RuleError::code).contains("out_of_range");

    StringBuilder wide = new StringBuilder("{\"any\":[");
    for (int i = 0; i < RuleCompiler.MAX_NODES; i++) {
      wide.append(i == 0 ? "" : ",")
          .append("{\"field\":\"tx_count_1h\",\"op\":\"eq\",\"value\":")
          .append(i)
          .append('}');
    }
    wide.append("]}");
    assertThat(errorsOf(wide.toString()))
        .extracting(RuleError::code)
        .containsExactly("item_count_out_of_range");
  }

  @Test
  void ruleSetReportsTheHighestOverrideAndEveryMatch() {
    CompiledRule medium =
        new CompiledRule(
            UUID.randomUUID(),
            1,
            TierOverride.MEDIUM,
            rule("{\"field\":\"tx_count_1h\",\"op\":\"gte\",\"value\":3}"));
    CompiledRule high =
        new CompiledRule(
            UUID.randomUUID(),
            2,
            TierOverride.HIGH,
            rule("{\"field\":\"tx_count_1h\",\"op\":\"gte\",\"value\":10}"));
    RuleSet rules = new RuleSet(7, List.of(medium, high));
    assertThat(rules.evaluate(subject("tx_count_1h", 1)).override()).isEmpty();
    assertThat(rules.evaluate(subject("tx_count_1h", 4)).override()).contains(TierOverride.MEDIUM);
    RuleSet.Evaluation both = rules.evaluate(subject("tx_count_1h", 12));
    assertThat(both.override()).contains(TierOverride.HIGH);
    assertThat(both.matched()).containsExactly(medium, high);
    assertThat(RuleSet.EMPTY.evaluate(subject()).matched()).isEmpty();
    assertThatThrownBy(
            () -> new CompiledRule(UUID.randomUUID(), 0, TierOverride.HIGH, s -> Truth.TRUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void operatorsRoundTripTheirWireNames() {
    for (Operator operator : Operator.values()) {
      assertThat(Operator.parse(operator.wireName())).contains(operator);
    }
    assertThat(Operator.parse("NOT_IN")).isEmpty();
  }

  @Test
  void catalogueHoldsExactlyTheFortyFourFeatures() {
    assertThat(FieldCatalogue.featureNames()).hasSize(FieldCatalogue.FEATURE_COUNT);
    assertThat(FieldCatalogue.find("merchant_name")).isEmpty();
    assertThat(FieldCatalogue.find("channel").orElseThrow().source())
        .isEqualTo(FieldSource.REQUEST);
    assertThat(FieldCatalogue.all())
        .extracting(RuleField::name)
        .contains("corridor_class", "amount");
  }
}
