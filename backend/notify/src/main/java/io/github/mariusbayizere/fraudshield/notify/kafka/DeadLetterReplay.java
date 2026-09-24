package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Replays dead-lettered records to the topic they came from (docs/architecture/decision-fact-
 * ordering.md, D-c). Operators run it from the ingest image once the cause is gone, for example
 * {@code parent_not_recorded} once {@code fs_spool_lag{consumer="postgres"}} is zero everywhere
 * (D-c2).
 *
 * <p>It reads {@code <topic>.dlq} with no consumer group, from a time to the end offsets it finds
 * when it starts, so it commits nothing: records of reasons it was not asked to replay stay for a
 * later run. Of several copies of one envelope {@code event_id} it republishes only the newest,
 * with its key, value and original headers, without the {@code fs-dlq-*} headers, and with {@code
 * fs-replayed} incremented. A replayed record never waits: if its parent is still missing it
 * returns to the DLQ at once (D-d). {@code rejected_by_the_database} is refused unless allowed
 * explicitly, because such an SMS may already have been delivered.
 */
public final class DeadLetterReplay {

  /** The reason that is refused unless the operator allows it. */
  public static final String REJECTED = "rejected_by_the_database";

  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterReplay.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private DeadLetterReplay() {}

  /**
   * What a run did.
   *
   * @param republished records republished
   * @param olderCopies copies not republished because a newer copy of the same event was
   * @param skipped records left in place, by reason
   */
  public record Result(int republished, int olderCopies, Map<String, Integer> skipped) {

    /** Copies the counts. */
    public Result {
      skipped = Map.copyOf(skipped);
    }
  }

  /**
   * Replays.
   *
   * @param kafka Kafka client properties: bootstrap servers and security
   * @param topic the source topic; its DLQ is {@code <topic>.dlq}
   * @param reasons the dead-letter reasons to replay
   * @param from the earliest dead-letter time to replay
   * @param allowRejected whether {@value #REJECTED} may be replayed
   * @return what the run did
   */
  public static Result replay(
      Map<String, Object> kafka,
      String topic,
      Set<String> reasons,
      Instant from,
      boolean allowRejected) {
    Objects.requireNonNull(topic, "topic");
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("name at least one reason to replay");
    }
    if (reasons.contains(REJECTED)) {
      if (!allowRejected) {
        throw new IllegalArgumentException(
            REJECTED + " is replayed only with --allow-rejected: such an SMS may have been sent");
      }
      LOG.warn("replaying {}: each such SMS may already have been delivered", REJECTED);
    }
    String dlq = topic + ".dlq";
    Map<String, ConsumerRecord<String, byte[]>> newest = new LinkedHashMap<>();
    Map<String, Integer> skipped = new TreeMap<>();
    int olderCopies = 0;
    Map<String, Object> consumerProperties = new HashMap<>(kafka);
    consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    consumerProperties.remove(ConsumerConfig.GROUP_ID_CONFIG);
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            consumerProperties, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> partitions = new ArrayList<>();
      consumer
          .partitionsFor(dlq)
          .forEach(p -> partitions.add(new TopicPartition(dlq, p.partition())));
      consumer.assign(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      Map<TopicPartition, Long> query = new HashMap<>();
      partitions.forEach(p -> query.put(p, from.toEpochMilli()));
      Map<TopicPartition, OffsetAndTimestamp> start = consumer.offsetsForTimes(query);
      for (TopicPartition p : partitions) {
        OffsetAndTimestamp at = start.get(p);
        consumer.seek(p, at == null ? end.get(p) : at.offset());
      }
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
          TopicPartition p = new TopicPartition(record.topic(), record.partition());
          if (record.offset() >= end.get(p)) {
            continue;
          }
          String reason = header(record, "fs-dlq-reason");
          if (reason == null || !reasons.contains(reason)) {
            skipped.merge(reason == null ? "unknown" : reason, 1, Integer::sum);
            continue;
          }
          String event = eventId(record);
          ConsumerRecord<String, byte[]> previous = newest.put(event, record);
          if (previous != null) {
            olderCopies++;
            if (previous.timestamp() > record.timestamp()) {
              newest.put(event, previous);
            }
          }
        }
      }
    }
    Map<String, Object> producerProperties = new HashMap<>(kafka);
    producerProperties
        .keySet()
        .removeIf(
            name ->
                ConsumerConfig.configNames().contains(name)
                    && !ProducerConfig.configNames().contains(name));
    producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
    producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    int republished = 0;
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            producerProperties, new StringSerializer(), new ByteArraySerializer())) {
      for (ConsumerRecord<String, byte[]> record : newest.values()) {
        String target = header(record, "fs-dlq-topic");
        ProducerRecord<String, byte[]> again =
            new ProducerRecord<>(target == null ? topic : target, record.key(), record.value());
        int replayed = 0;
        for (Header h : record.headers()) {
          if (h.key().equals(EnvelopeConsumer.REPLAYED_HEADER)) {
            replayed = Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8).trim());
          } else if (!h.key().startsWith(EnvelopeConsumer.DLQ_HEADER_PREFIX)) {
            again.headers().add(h.key(), h.value());
          }
        }
        again
            .headers()
            .add(
                EnvelopeConsumer.REPLAYED_HEADER,
                Integer.toString(replayed + 1).getBytes(StandardCharsets.UTF_8));
        try {
          producer.send(again).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new IllegalStateException(
              "a replay failed after " + republished + " records; run it again: it is idempotent",
              e);
        }
        republished++;
      }
    }
    return new Result(republished, olderCopies, skipped);
  }

  private static String header(ConsumerRecord<String, byte[]> record, String name) {
    Header h = record.headers().lastHeader(name);
    return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  private static String eventId(ConsumerRecord<String, byte[]> record) {
    String header = header(record, "event_id");
    if (header != null) {
      return header;
    }
    try {
      return JSON.readTree(record.value()).get("event_id").asString();
    } catch (RuntimeException unreadable) {
      // Not an envelope: keep every copy apart.
      return record.topic() + ":" + record.partition() + ":" + record.offset();
    }
  }

  /**
   * Runs a replay: {@code --bootstrap-servers <servers> --topic <source topic> [--reason
   * <reason>]... [--from <ISO-8601 instant>] [--allow-rejected]}. The default reason is {@code
   * parent_not_recorded}, and the default start is 30 days ago, the DLQ's retention.
   *
   * @param args the arguments
   */
  public static void main(String[] args) {
    Map<String, Object> kafka = new HashMap<>();
    String topic = null;
    Set<String> reasons = new java.util.LinkedHashSet<>();
    Instant from = Instant.now().minus(Duration.ofDays(30));
    boolean allowRejected = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--bootstrap-servers" -> kafka.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, args[++i]);
        case "--topic" -> topic = args[++i];
        case "--reason" -> reasons.add(args[++i]);
        case "--from" -> from = Instant.parse(args[++i]);
        case "--allow-rejected" -> allowRejected = true;
        default -> throw new IllegalArgumentException("unknown argument " + args[i]);
      }
    }
    if (reasons.isEmpty()) {
      reasons.add("parent_not_recorded");
    }
    Result result = replay(kafka, topic, reasons, from, allowRejected);
    System.out.printf(
        "republished %d, older copies not republished %d, left in place %s%n",
        result.republished(), result.olderCopies(), result.skipped());
  }
}
