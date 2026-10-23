package io.github.mariusbayizere.fraudshield.ingest.request;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Validates an ingest request exactly as ADR 0011 section 6 and the shared vectors in {@code
 * contracts/validation/request-validation-vectors.json} specify: every error is reported, one code
 * per field, and the status is 400 when any error makes the body unusable (missing, unknown or
 * non-token fields, malformed JSON), otherwise 422. Raw phone or account numbers are refused as
 * {@code not_a_token} so personal data never enters the system (E.1, NFR-SEC-03).
 */
public final class RequestValidator {

  /** Largest accepted clock skew into the future (E.1). */
  public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

  /** Fields of {@code TransactionIngestRequest}, in schema order. */
  public static final List<String> FIELDS =
      List.of(
          "transaction_id",
          "account_id",
          "counterparty_id",
          "amount",
          "currency",
          "channel",
          "merchant_category_code",
          "merchant_name",
          "latitude",
          "longitude",
          "device_fingerprint",
          "agent_id",
          "counterparty_country",
          "transaction_timestamp");

  private static final List<String> REQUIRED =
      List.of(
          "transaction_id",
          "account_id",
          "counterparty_id",
          "amount",
          "currency",
          "channel",
          "merchant_category_code",
          "latitude",
          "longitude",
          "transaction_timestamp");

  private static final Pattern TOKEN = Pattern.compile("^tok_[A-Za-z0-9]{24,64}$");
  private static final Pattern UUID_FORMAT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern MCC = Pattern.compile("^[0-9]{4}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern TIMESTAMP =
      Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$");

  /**
   * The outcome: a request or its errors.
   *
   * @param request the validated request, or null
   * @param errors errors, empty when valid
   */
  public record Result(IngestRequest request, List<ValidationError> errors) {
    /** Copies the errors. */
    public Result {
      errors = List.copyOf(errors);
    }

    /**
     * The HTTP status for the errors.
     *
     * @return 400 when any error is 400-class, else 422
     */
    public int status() {
      return errors.stream().anyMatch(ValidationError::badRequest) ? 400 : 422;
    }
  }

  private RequestValidator() {}

  /**
   * The error for a body that is not JSON.
   *
   * @return the result
   */
  public static Result malformed() {
    return new Result(
        null, List.of(new ValidationError("", "malformed_json", "the body is not valid JSON")));
  }

  /**
   * Validates a request.
   *
   * @param body the parsed body
   * @param now server time, for the future-timestamp check
   * @return the request or its errors
   */
  public static Result validate(JsonNode body, Instant now) {
    if (body == null || !body.isObject()) {
      return new Result(
          null,
          List.of(new ValidationError("", "type_mismatch", "the body must be a JSON object")));
    }
    List<ValidationError> errors = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>(body.propertyNames());
    for (String name : names) {
      if (!FIELDS.contains(name)) {
        errors.add(new ValidationError(name, "unknown_field", "not a field of this request"));
      }
    }
    for (String name : REQUIRED) {
      if (!body.has(name)) {
        errors.add(new ValidationError(name, "required", name + " is required"));
      }
    }
    UUID transactionId = uuid(body, "transaction_id", errors);
    String account = token(body, "account_id", errors, false);
    String counterparty = token(body, "counterparty_id", errors, false);
    BigDecimal amount = amount(body, errors);
    CurrencyCode currency = enumeration(body, "currency", errors, CurrencyCode.class);
    Channel channel = enumeration(body, "channel", errors, Channel.class);
    String mcc = pattern(body, "merchant_category_code", MCC, errors);
    String merchantName = text(body, "merchant_name", 100, errors);
    Double latitude = number(body, "latitude", -90, 90, errors);
    Double longitude = number(body, "longitude", -180, 180, errors);
    String device = token(body, "device_fingerprint", errors, true);
    String agent = token(body, "agent_id", errors, false);
    String country = pattern(body, "counterparty_country", COUNTRY, errors);
    Instant timestamp = timestamp(body, now, errors);
    if (body.path("channel").isString()
        && "AGENT_BANKING".equals(body.get("channel").asString())
        && !body.has("agent_id")) {
      errors.add(
          new ValidationError("agent_id", "required", "agent_id is required for AGENT_BANKING"));
    }
    if (!errors.isEmpty()) {
      return new Result(null, errors);
    }
    return new Result(
        new IngestRequest(
            transactionId,
            account,
            counterparty,
            new Money(amount, currency),
            channel,
            mcc,
            merchantName,
            latitude,
            longitude,
            device,
            agent,
            country,
            timestamp),
        List.of());
  }

  private static UUID uuid(JsonNode body, String name, List<ValidationError> errors) {
    String value = string(body, name, errors);
    if (value == null) {
      return null;
    }
    if (!UUID_FORMAT.matcher(value).matches()) {
      errors.add(new ValidationError(name, "invalid_format", name + " must be a UUID"));
      return null;
    }
    return UUID.fromString(value);
  }

  private static String token(
      JsonNode body, String name, List<ValidationError> errors, boolean nullable) {
    if (nullable && body.has(name) && body.get(name).isNull()) {
      return null;
    }
    String value = string(body, name, errors);
    if (value != null && !TOKEN.matcher(value).matches()) {
      errors.add(
          new ValidationError(
              name,
              "not_a_token",
              name + " must be a token issued by the tokenisation service, never a raw number"));
      return null;
    }
    return value;
  }

  private static BigDecimal amount(JsonNode body, List<ValidationError> errors) {
    String value = string(body, "amount", errors);
    if (value == null) {
      return null;
    }
    if (!canonicalDecimal(value)) {
      errors.add(
          new ValidationError(
              "amount", "invalid_format", "amount is a decimal string such as 15000.50"));
      return null;
    }
    BigDecimal amount = new BigDecimal(value);
    int dot = value.indexOf('.');
    String integers = (dot < 0 ? value : value.substring(0, dot)).replace("-", "");
    if (amount.signum() < 0 || integers.length() > 14) {
      errors.add(
          new ValidationError(
              "amount", "out_of_range", "amount must be positive with at most 14 integer digits"));
      return null;
    }
    if (dot >= 0 && value.length() - dot - 1 > 4) {
      errors.add(new ValidationError("amount", "invalid_format", "amount has at most 4 decimals"));
      return null;
    }
    if (amount.signum() == 0) {
      errors.add(new ValidationError("amount", "out_of_range", "amount must be above zero"));
      return null;
    }
    return amount;
  }

  /**
   * Whether a string is {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?}, checked in one linear pass.
   *
   * @param value the string
   * @return true for a canonical decimal
   */
  static boolean canonicalDecimal(String value) {
    int i = value.startsWith("-") ? 1 : 0;
    int start = i;
    while (i < value.length() && Character.isDigit(value.charAt(i)) && value.charAt(i) < 128) {
      i++;
    }
    int integers = i - start;
    if (integers == 0 || (integers > 1 && value.charAt(start) == '0')) {
      return false;
    }
    if (i == value.length()) {
      return true;
    }
    if (value.charAt(i) != '.' || i == value.length() - 1) {
      return false;
    }
    for (int j = i + 1; j < value.length(); j++) {
      char c = value.charAt(j);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static <E extends Enum<E>> E enumeration(
      JsonNode body, String name, List<ValidationError> errors, Class<E> type) {
    String value = string(body, name, errors);
    if (value == null) {
      return null;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(value)) {
        return constant;
      }
    }
    errors.add(new ValidationError(name, "unsupported_value", name + " is not supported"));
    return null;
  }

  private static String pattern(
      JsonNode body, String name, Pattern pattern, List<ValidationError> errors) {
    String value = string(body, name, errors);
    if (value != null && !pattern.matcher(value).matches()) {
      errors.add(new ValidationError(name, "invalid_format", name + " has the wrong format"));
      return null;
    }
    return value;
  }

  private static String text(
      JsonNode body, String name, int maxLength, List<ValidationError> errors) {
    String value = string(body, name, errors);
    if (value != null && value.codePointCount(0, value.length()) > maxLength) {
      errors.add(
          new ValidationError(
              name, "length_out_of_range", name + " has at most " + maxLength + " characters"));
      return null;
    }
    return value;
  }

  private static Double number(
      JsonNode body, String name, double min, double max, List<ValidationError> errors) {
    JsonNode node = body.get(name);
    if (node == null) {
      return null;
    }
    if (!node.isNumber()) {
      errors.add(new ValidationError(name, "type_mismatch", name + " is a JSON number"));
      return null;
    }
    double value = node.asDouble();
    if (!Double.isFinite(value) || value < min || value > max) {
      errors.add(
          new ValidationError(
              name, "out_of_range", name + " is between " + (int) min + " and " + (int) max));
      return null;
    }
    return value;
  }

  private static Instant timestamp(JsonNode body, Instant now, List<ValidationError> errors) {
    String value = string(body, "transaction_timestamp", errors);
    if (value == null) {
      return null;
    }
    Instant at;
    try {
      if (!TIMESTAMP.matcher(value).matches()) {
        throw new DateTimeParseException("not RFC 3339 UTC", value, 0);
      }
      at = Instant.parse(value);
    } catch (DateTimeParseException e) {
      errors.add(
          new ValidationError(
              "transaction_timestamp",
              "invalid_format",
              "transaction_timestamp is RFC 3339 in UTC with a Z suffix"));
      return null;
    }
    if (at.isAfter(now.plus(FUTURE_TOLERANCE))) {
      errors.add(
          new ValidationError(
              "transaction_timestamp",
              "timestamp_in_future",
              "transaction_timestamp is more than 5 minutes ahead of the server clock"));
      return null;
    }
    return at;
  }

  /** A string field; reports a type mismatch for any other JSON type, including null. */
  private static String string(JsonNode body, String name, List<ValidationError> errors) {
    JsonNode node = body.get(name);
    if (node == null) {
      return null;
    }
    if (!node.isString()) {
      errors.add(new ValidationError(name, "type_mismatch", name + " is a string"));
      return null;
    }
    return node.asString();
  }
}
