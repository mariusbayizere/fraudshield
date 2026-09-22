package io.github.mariusbayizere.fraudshield.ingest.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-01-02: the ingest request against every shared vector (ADR 0011): the same status and exactly
 * the same (field, code) pairs as the contract tests expect.
 */
@Tag("FR-01-02")
@Tag("NFR-SEC-03")
class RequestValidatorTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-09-17T08:16:00Z");

  /** A valid request with every fingerprinted field present, including the optional ones. */
  private static final String FULL_REQUEST =
      """
      {"transaction_id":"3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11",
       "account_id":"tok_A1b2C3d4E5f6G7h8J9k0L1m2",
       "counterparty_id":"tok_Z9y8X7w6V5u4T3s2R1q0P9o8",
       "amount":"15000","currency":"RWF","channel":"MOBILE_MONEY",
       "merchant_category_code":"4829","merchant_name":"Synthetic Supermarket 001",
       "latitude":-1.9441,"longitude":30.0619,
       "device_fingerprint":"tok_D3v1c3F1ng3rpr1ntAaBbCc0",
       "agent_id":"tok_Ag3ntK4mp4l4C3ntr4l00004","counterparty_country":"RW",
       "transaction_timestamp":"2026-09-17T08:15:30Z"}
      """;

  static Stream<Arguments> vectors() throws Exception {
    JsonNode root =
        JSON.readTree(
            Files.readString(
                Path.of("..", "..", "contracts", "validation", "request-validation-vectors.json")));
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode vector : root.get("schemas").get("TransactionIngestRequest")) {
      cases.add(Arguments.of(vector.get("label").asString(), vector));
    }
    assertThat(cases).hasSizeGreaterThanOrEqualTo(26);
    return cases.stream();
  }

  static RequestValidator.Result validate(JsonNode vector) {
    if (vector.has("raw_body")) {
      try {
        return RequestValidator.validate(JSON.readTree(vector.get("raw_body").asString()), NOW);
      } catch (JacksonException malformed) {
        return RequestValidator.malformed();
      }
    }
    return RequestValidator.validate(vector.get("body"), NOW);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void everyVectorGetsItsStatusAndExactlyItsErrors(String label, JsonNode vector) {
    RequestValidator.Result result = validate(vector);
    List<String> expected = new ArrayList<>();
    vector
        .get("expected_errors")
        .forEach(e -> expected.add(e.get("field").asString() + " " + e.get("code").asString()));
    List<String> actual = result.errors().stream().map(e -> e.field() + " " + e.code()).toList();
    assertThat(actual).as(label).containsExactlyInAnyOrderElementsOf(expected);
    int expectedStatus = vector.get("expected_status").asInt();
    if (expectedStatus < 300) {
      assertThat(result.request()).as(label).isNotNull();
    } else {
      assertThat(result.status()).as(label).isEqualTo(expectedStatus);
    }
  }

  @Test
  @Tag("FR-01-03")
  void fingerprintsIgnoreFormattingButNotValues() throws Exception {
    String base =
        "{\"transaction_id\":\"3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11\","
            + "\"account_id\":\"tok_A1b2C3d4E5f6G7h8J9k0L1m2\","
            + "\"counterparty_id\":\"tok_Z9y8X7w6V5u4T3s2R1q0P9o8\",\"amount\":\"AMOUNT\","
            + "\"currency\":\"RWF\",\"channel\":\"USSD\",\"merchant_category_code\":\"4829\","
            + "\"latitude\":-1.9441,\"longitude\":30.0619,\"device_fingerprint\":null,"
            + "\"transaction_timestamp\":\"TIME\"}";
    byte[] a =
        Fingerprints.of(
            RequestValidator.validate(
                    JSON.readTree(
                        base.replace("AMOUNT", "15000").replace("TIME", "2026-09-17T08:15:30Z")),
                    NOW)
                .request());
    byte[] b =
        Fingerprints.of(
            RequestValidator.validate(
                    JSON.readTree(
                        base.replace("AMOUNT", "15000.00")
                            .replace("TIME", "2026-09-17T08:15:30.000Z")),
                    NOW)
                .request());
    byte[] c =
        Fingerprints.of(
            RequestValidator.validate(
                    JSON.readTree(
                        base.replace("AMOUNT", "15000.01").replace("TIME", "2026-09-17T08:15:30Z")),
                    NOW)
                .request());
    assertThat(a).hasSize(32).isEqualTo(b).isNotEqualTo(c);
  }

  /**
   * Every value the API is given must be part of the fingerprint: a submission that differs in any
   * of them is not a duplicate of the first and must be a 409, not a replay (FR-01-03). The earlier
   * test varies the amount alone, which let a field be dropped from {@link Fingerprints} without a
   * failing test (Principal Review finding 4).
   */
  @ParameterizedTest(name = "{0} changes the fingerprint")
  @Tag("FR-01-03")
  @MethodSource("fingerprintedFields")
  void everyRequestValueChangesTheFingerprint(String field, String changed) throws Exception {
    assertThat(fingerprint(FULL_REQUEST)).isNotEqualTo(fingerprint(changed));
  }

  static Stream<Arguments> fingerprintedFields() {
    return Stream.of(
        Arguments.of(
            "transaction_id",
            swap("3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11", "3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a12")),
        Arguments.of(
            "account_id", swap("tok_A1b2C3d4E5f6G7h8J9k0L1m2", "tok_A1b2C3d4E5f6G7h8J9k0L1m3")),
        Arguments.of(
            "counterparty_id",
            swap("tok_Z9y8X7w6V5u4T3s2R1q0P9o8", "tok_Z9y8X7w6V5u4T3s2R1q0P9o9")),
        Arguments.of("amount", swap("\"15000\"", "\"15001\"")),
        Arguments.of("currency", swap("\"RWF\"", "\"USD\"")),
        Arguments.of("channel", swap("\"MOBILE_MONEY\"", "\"CARD\"")),
        Arguments.of("merchant_category_code", swap("\"4829\"", "\"5411\"")),
        Arguments.of(
            "merchant_name", swap("Synthetic Supermarket 001", "Synthetic Supermarket 002")),
        Arguments.of("latitude", swap("-1.9441", "-1.9442")),
        Arguments.of("longitude", swap("30.0619", "30.0620")),
        Arguments.of(
            "device_fingerprint",
            swap("tok_D3v1c3F1ng3rpr1ntAaBbCc0", "tok_D3v1c3F1ng3rpr1ntAaBbCc1")),
        Arguments.of(
            "agent_id", swap("tok_Ag3ntK4mp4l4C3ntr4l00004", "tok_Ag3ntK4mp4l4C3ntr4l00005")),
        Arguments.of("counterparty_country", swap("\"RW\"", "\"KE\"")),
        Arguments.of(
            "transaction_timestamp", swap("2026-09-17T08:15:30Z", "2026-09-17T08:15:31Z")));
  }

  /** The formatting differences that must not change it, each on its own. */
  @ParameterizedTest(name = "{0} does not change the fingerprint")
  @Tag("FR-01-03")
  @MethodSource("formattingOnly")
  void normalisedFormattingLeavesTheFingerprintAlone(String what, String same) throws Exception {
    assertThat(fingerprint(FULL_REQUEST)).isEqualTo(fingerprint(same));
  }

  static Stream<Arguments> formattingOnly() {
    return Stream.of(
        Arguments.of("trailing zeros in the amount", swap("\"15000\"", "\"15000.00\"")),
        Arguments.of(
            "fractional seconds", swap("2026-09-17T08:15:30Z", "2026-09-17T08:15:30.000Z")),
        Arguments.of(
            "more fractional digits", swap("2026-09-17T08:15:30Z", "2026-09-17T08:15:30.000000Z")),
        Arguments.of("a decimal point with no cents", swap("\"15000\"", "\"15000.0\"")));
  }

  private static String swap(String from, String to) {
    assertThat(FULL_REQUEST).contains(from);
    return FULL_REQUEST.replace(from, to);
  }

  private static byte[] fingerprint(String body) throws JacksonException {
    RequestValidator.Result result = RequestValidator.validate(JSON.readTree(body), NOW);
    assertThat(result.errors()).isEmpty();
    return Fingerprints.of(result.request());
  }

  @Test
  void nonObjectBodiesAndNullRequiredFieldsAreTypeMismatches() throws Exception {
    assertThat(RequestValidator.validate(JSON.readTree("[1]"), NOW).errors())
        .extracting(ValidationError::code)
        .containsExactly("type_mismatch");
    RequestValidator.Result nullCurrency =
        RequestValidator.validate(
            JSON.readTree(
                "{\"transaction_id\":\"3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11\","
                    + "\"account_id\":\"tok_A1b2C3d4E5f6G7h8J9k0L1m2\","
                    + "\"counterparty_id\":\"tok_Z9y8X7w6V5u4T3s2R1q0P9o8\",\"amount\":\"1\","
                    + "\"currency\":null,\"channel\":\"CARD\",\"merchant_category_code\":\"4829\","
                    + "\"latitude\":0,\"longitude\":0,\"merchant_name\":\""
                    + "x".repeat(101)
                    + "\","
                    + "\"transaction_timestamp\":\"2026-09-17T08:15:30Z\"}"),
            NOW);
    assertThat(nullCurrency.errors())
        .extracting(e -> e.field() + " " + e.code())
        .containsExactlyInAnyOrder("currency type_mismatch", "merchant_name length_out_of_range");
    assertThat(nullCurrency.status()).isEqualTo(422);
  }
}
