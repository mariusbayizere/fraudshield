package io.github.mariusbayizere.fraudshield.ingest.api;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Everything the running system has written to Kafka, Redis and the spool, as text, so a test can
 * show that a customer's phone number or account number is in none of it (D-20).
 *
 * <p>Bytes are read as ISO-8859-1, which maps every byte to one character, so a value embedded in a
 * binary record is still found as a substring.
 */
final class PlaintextScan {

  private PlaintextScan() {}

  /**
   * Every record on every topic, uncommitted and aborted transactional records included.
   *
   * @param bootstrap the cluster
   * @return one string per record: topic, key, headers and value
   */
  static List<String> kafka(String bootstrap) {
    List<String> out = new ArrayList<>();
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      List<TopicPartition> partitions = new ArrayList<>();
      for (var description :
          admin.describeTopics(admin.listTopics().names().get()).allTopicNames().get().values()) {
        for (var partition : description.partitions()) {
          partitions.add(new TopicPartition(description.name(), partition.partition()));
        }
      }
      Properties properties = new Properties();
      properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
      properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
      try (KafkaConsumer<byte[], byte[]> consumer =
          new KafkaConsumer<>(
              properties, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!caughtUp(consumer, end) && System.nanoTime() < deadline) {
          for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(200))) {
            StringBuilder text = new StringBuilder(r.topic()).append(' ');
            text.append(latin1(r.key())).append(' ');
            for (Header h : r.headers()) {
              text.append(h.key()).append('=').append(latin1(h.value())).append(' ');
            }
            out.add(text.append(latin1(r.value())).toString());
          }
        }
        if (!caughtUp(consumer, end)) {
          throw new IllegalStateException("could not read every topic to its end");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static boolean caughtUp(
      KafkaConsumer<byte[], byte[]> consumer, Map<TopicPartition, Long> end) {
    for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
      if (consumer.position(e.getKey()) < e.getValue()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Every key and value in Redis, whatever its type.
   *
   * @param connection a connection to the instance
   * @return one string per key
   */
  static List<String> redis(StatefulRedisConnection<String, String> connection) {
    RedisCommands<String, String> redis = connection.sync();
    List<String> out = new ArrayList<>();
    ScanCursor cursor = ScanCursor.INITIAL;
    do {
      KeyScanCursor<String> page = redis.scan(cursor, ScanArgs.Builder.limit(500));
      for (String key : page.getKeys()) {
        String type = redis.type(key);
        Object value =
            switch (type) {
              case "string" -> redis.get(key);
              case "hash" -> redis.hgetall(key);
              case "list" -> redis.lrange(key, 0, -1);
              case "set" -> redis.smembers(key);
              case "zset" -> redis.zrange(key, 0, -1);
              case "stream" -> redis.xrange(key, io.lettuce.core.Range.create("-", "+"));
              default -> "";
            };
        out.add(key + " " + value);
      }
      cursor = page;
    } while (!cursor.isFinished());
    return out;
  }

  /**
   * Every file under a directory.
   *
   * @param root the directory
   * @return one string per file
   */
  static List<String> files(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      List<String> out = new ArrayList<>();
      for (Path p : paths.filter(Files::isRegularFile).toList()) {
        out.add(p + " " + latin1(Files.readAllBytes(p)));
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String latin1(byte[] bytes) {
    return bytes == null ? "" : new String(bytes, StandardCharsets.ISO_8859_1);
  }
}
