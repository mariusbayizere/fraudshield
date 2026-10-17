package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * The {@code fs-transaction-id} header of a customer SMS intent, in one of three states (docs/
 * architecture/decision-fact-ordering.md, 5.1): absent; valid, exactly one value that is a UUID in
 * canonical form; or malformed, anything else that is present, a repeated or null-valued header
 * included. A malformed header never decides a send: it only stands in for the transaction where B
 * is absent (S3m).
 *
 * @param state the state
 * @param transactionId the transaction, when valid
 */
public record TransactionHeader(State state, UUID transactionId) {

  /** The header's states. */
  public enum State {
    /** Not on the record: an intent already on the topic at deployment, or its DLQ copy. */
    ABSENT,
    /** Exactly one canonical UUID. */
    VALID,
    /** Present but not exactly one canonical UUID. */
    MALFORMED
  }

  /** The absent header. */
  public static final TransactionHeader ABSENT = new TransactionHeader(State.ABSENT, null);

  /** The malformed header. */
  public static final TransactionHeader MALFORMED = new TransactionHeader(State.MALFORMED, null);

  /** A canonical UUID: five hexadecimal groups of 8, 4, 4, 4 and 12, separated by hyphens. */
  static final Pattern CANONICAL_UUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /** Validates the state against the id. */
  public TransactionHeader {
    Objects.requireNonNull(state, "state");
    if ((state == State.VALID) != (transactionId != null)) {
      throw new IllegalArgumentException("only a valid header names a transaction");
    }
  }

  /**
   * A valid header.
   *
   * @param transactionId the transaction
   * @return the header
   */
  public static TransactionHeader valid(UUID transactionId) {
    return new TransactionHeader(State.VALID, Objects.requireNonNull(transactionId));
  }

  /**
   * The header's state on a record, from every value it carries, before any map that keeps one
   * value per name (5.1).
   *
   * @param headers the record's headers
   * @param name the header's name
   * @return the state
   */
  public static TransactionHeader of(Headers headers, String name) {
    List<byte[]> values = new ArrayList<>();
    for (Header header : headers.headers(name)) {
      values.add(header.value());
    }
    if (values.isEmpty()) {
      return ABSENT;
    }
    if (values.size() != 1 || values.getFirst() == null) {
      return MALFORMED;
    }
    return parse(new String(values.getFirst(), StandardCharsets.UTF_8));
  }

  /**
   * The state of one present value.
   *
   * @param value the value
   * @return valid for a canonical UUID, malformed otherwise
   */
  public static TransactionHeader parse(String value) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      return MALFORMED;
    }
    return valid(UUID.fromString(value));
  }

  /**
   * The transaction, when the header is valid.
   *
   * @return the transaction, or empty
   */
  public Optional<UUID> transaction() {
    return Optional.ofNullable(transactionId);
  }
}
