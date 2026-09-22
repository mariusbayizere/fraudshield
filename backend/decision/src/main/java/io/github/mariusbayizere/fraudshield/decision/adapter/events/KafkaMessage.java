package io.github.mariusbayizere.fraudshield.decision.adapter.events;

import java.util.Objects;

/**
 * One Kafka record: topic, partitioning key and the JSON envelope (ADR 0012).
 *
 * @param topic topic from {@code contracts/kafka/topics.yaml}
 * @param key partitioning key
 * @param eventId the envelope's event id, stable across re-publication
 * @param value UTF-8 JSON envelope
 */
public record KafkaMessage(String topic, String key, String eventId, byte[] value) {

  /** Requires every component and copies the value. */
  public KafkaMessage {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(eventId, "eventId");
    value = value.clone();
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
