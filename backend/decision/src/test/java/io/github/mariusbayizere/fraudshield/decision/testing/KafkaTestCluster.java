package io.github.mariusbayizere.fraudshield.decision.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A single-broker Kafka for integration tests (image as in docker-compose.yml), with every topic of
 * {@code contracts/kafka/topics.yaml} created up front, because producers never auto-create topics
 * (ADR 0012).
 */
public final class KafkaTestCluster implements AutoCloseable {

  /** Image of the Kafka service in docker-compose.yml. */
  public static final String IMAGE = "apache/kafka:4.3.1";

  private final KafkaContainer container;

  /** Starts the broker and creates the topics. */
  public KafkaTestCluster() {
    container = new KafkaContainer(IMAGE).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    container.start();
    createTopics();
  }

  /**
   * Bootstrap servers.
   *
   * @return host:port
   */
  public String bootstrapServers() {
    return container.getBootstrapServers();
  }

  /** Freezes the broker process, as a network partition or a hung broker would. */
  public void pause() {
    container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
  }

  /** Resumes a paused broker. */
  public void unpause() {
    container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
  }

  /**
   * Reads a topic from the beginning until {@code expected} distinct keys are seen or the timeout
   * passes.
   *
   * @param topic topic
   * @param keyOf extracts the reconciliation key from a record
   * @param expected distinct keys to wait for
   * @param timeout how long to wait
   * @return every record read
   */
  public List<ConsumerRecord<String, byte[]>> readAll(
      String topic,
      java.util.function.Function<ConsumerRecord<String, byte[]>, String> keyOf,
      int expected,
      Duration timeout) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
    java.util.Set<String> keys = new java.util.HashSet<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + timeout.toNanos();
      while (keys.size() < expected && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
          records.add(record);
          keys.add(keyOf.apply(record));
        }
      }
    }
    return records;
  }

  private void createTopics() {
    Pattern name = Pattern.compile("(?m)^  - name: (\\S+)$");
    List<NewTopic> topics = new ArrayList<>();
    try {
      Matcher matcher =
          name.matcher(
              Files.readString(ContractSchemas.REPOSITORY.resolve("contracts/kafka/topics.yaml")));
      while (matcher.find()) {
        topics.add(new NewTopic(matcher.group(1), 3, (short) 1));
        topics.add(new NewTopic(matcher.group(1) + ".dlq", 1, (short) 1));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
      admin.createTopics(topics).all().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("could not create topics", e);
    }
  }

  @Override
  public void close() {
    container.stop();
  }
}
