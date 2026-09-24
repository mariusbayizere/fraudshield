package io.github.mariusbayizere.fraudshield.decision.adapter.events;

import java.util.Map;
import java.util.Objects;

/**
 * One Kafka record: topic, partitioning key, the JSON envelope (ADR 0012), and headers outside the
 * envelope.
 *
 * @param topic topic from {@code contracts/kafka/topics.yaml}
 * @param key partitioning key
 * @param eventId the envelope's event id, stable across re-publication
 * @param value UTF-8 JSON envelope
 * @param headers further record headers, for example {@code fs-transaction-id} on a customer SMS
 *     intent (docs/architecture/decision-fact-ordering.md, section 4)
 */
public record KafkaMessage(
    String topic, String key, String eventId, byte[] value, Map<String, String> headers) {

  /** The name of the header carrying a customer SMS intent's transaction id. */
  public static final String TRANSACTION_ID_HEADER = "fs-transaction-id";

  /** Requires every component and copies the value and headers. */
  public KafkaMessage {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(eventId, "eventId");
    value = value.clone();
    headers = Map.copyOf(headers);
  }

  /**
   * A record without further headers.
   *
   * @param topic topic
   * @param key partitioning key
   * @param eventId the envelope's event id
   * @param value UTF-8 JSON envelope
   */
  public KafkaMessage(String topic, String key, String eventId, byte[] value) {
    this(topic, key, eventId, value, Map.of());
  }

  /**
   * The same record with one more header.
   *
   * @param name header name
   * @param value header value
   * @return the record
   */
  public KafkaMessage withHeader(String name, String value) {
    Map<String, String> more = new java.util.HashMap<>(headers);
    more.put(name, value);
    return new KafkaMessage(topic, key, eventId, this.value, more);
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KafkaMessage that
        && eventId.equals(that.eventId)
        && topic.equals(that.topic);
  }

  @Override
  public int hashCode() {
    return Objects.hash(topic, eventId);
  }

  @Override
  public String toString() {
    return "KafkaMessage[" + topic + ", " + key + ", " + eventId + "]";
  }
}
