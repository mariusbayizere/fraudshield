package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every instance reads every alert event (ADR 0115 point 5): one consumer, manually assigned every
 * partition of the four topics, with no group and no committed offsets. It starts at the end, is
 * ready only once positioned, picks up new partitions from their earliest offset, exports its lag,
 * and does nothing on its thread but poll and route. A staff name that is not cached leaves the
 * thread for the resolver and re-enters routing when resolved.
 */
final class FanOut implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(FanOut.class);

  private final Supplier<org.apache.kafka.clients.consumer.Consumer<String, byte[]>> consumers;
  private final AlertFrames frames;
  private final StaffNames names;
  private final SessionRegistry registry;
  private final Consumer<java.util.UUID> seen;
  private final RealtimeSettings settings;
  private final LongSupplier nanoTime;
  private final Consumer<TopicPartition> assignedHook;
  private final Map<TopicPartition, Long> lag = new ConcurrentHashMap<>();
  private final AtomicLong invalid = new AtomicLong();
  private final AtomicLong overflows = new AtomicLong();
  private final AtomicLong lagMisses = new AtomicLong();
  private final AtomicLong refreshMisses = new AtomicLong();

  /** How long resolving one partition's position may take (ASSUMED). */
  private static final Duration POSITION_TIMEOUT = Duration.ofSeconds(5);

  private final Thread thread;
  private volatile boolean ready;
  private volatile boolean running = true;
  private volatile long lastPollNanos;

  FanOut(
      Supplier<org.apache.kafka.clients.consumer.Consumer<String, byte[]>> consumers,
      AlertFrames frames,
      StaffNames names,
      SessionRegistry registry,
      Consumer<java.util.UUID> seen,
      RealtimeSettings settings,
      LongSupplier nanoTime,
      Consumer<TopicPartition> assignedHook) {
    this.consumers = consumers;
    this.assignedHook = assignedHook;
    this.frames = frames;
    this.names = names;
    this.registry = registry;
    this.seen = seen;
    this.settings = settings;
    this.nanoTime = nanoTime;
    this.lastPollNanos = nanoTime.getAsLong();
    this.thread = Thread.ofPlatform().name("fs-realtime-fanout").daemon().unstarted(this::run);
  }

  /** Starts the consumer thread; without Kafka there is none, and the instance is never ready. */
  void start() {
    if (consumers != null) {
      thread.start();
    }
  }

  private void run() {
    while (running) {
      consume();
      if (running) {
        // A failed consumer has closed its sessions through healthy(); it starts again from the
        // end, as a restarted instance does (point 5).
        try {
          Thread.sleep(5_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void consume() {
    try (org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer = consumers.get()) {
      Set<TopicPartition> assigned = new HashSet<>(partitions(consumer));
      consumer.assign(assigned);
      consumer.seekToEnd(assigned);
      // seekToEnd is lazy: position() fixes it before the instance reports ready (point 5).
      for (TopicPartition p : assigned) {
        positioned(consumer, p);
      }
      lastPollNanos = nanoTime.getAsLong();
      ready = true;
      long nextRefresh = nanoTime.getAsLong() + settings.partitionRefresh().toNanos();
      long nextLag = 0;
      while (running) {
        ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(100));
        lastPollNanos = nanoTime.getAsLong();
        for (ConsumerRecord<String, byte[]> record : polled) {
          try {
            route(record.topic(), record.value(), record.timestamp());
          } catch (RuntimeException failed) {
            // One record's failure never stops the consumer (code review 3, MAJOR 1).
            invalid.incrementAndGet();
            LOG.warn("a real-time record could not be routed; dropped", failed);
          }
        }
        long now = nanoTime.getAsLong();
        if (now - nextLag >= 0) {
          measureLag(consumer, assigned);
          nextLag = now + 5_000_000_000L;
        }
        if (now - nextRefresh >= 0) {
          assigned = refresh(consumer, assigned);
          nextRefresh = now + settings.partitionRefresh().toNanos();
        }
      }
    } catch (RuntimeException failed) {
      LOG.error("the real-time fan-out consumer stopped; its sessions are closed", failed);
    } finally {
      ready = false;
    }
  }

  private static List<TopicPartition> partitions(
      org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer) {
    List<TopicPartition> all = new ArrayList<>();
    for (String topic : AlertFrames.TOPICS) {
      List<PartitionInfo> infos = consumer.partitionsFor(topic);
      if (infos != null) {
        for (PartitionInfo info : infos) {
          all.add(new TopicPartition(info.topic(), info.partition()));
        }
      }
    }
    return all;
  }

  /** New partitions start from their earliest offset: at most one refresh period of data. */
  Set<TopicPartition> refresh(
      org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
      Set<TopicPartition> assigned) {
    // A refresh that times out keeps the current assignment and is tried again at the next one;
    // it never fails the consumer, which would close every session (code review 3, MINOR 6).
    try {
      Set<TopicPartition> now = new HashSet<>(partitions(consumer));
      now.addAll(assigned);
      if (now.equals(assigned)) {
        return assigned;
      }
      Set<TopicPartition> added = new HashSet<>(now);
      added.removeAll(assigned);
      consumer.assign(now);
      consumer.seekToBeginning(added);
      for (TopicPartition p : added) {
        resolvePosition(consumer, p);
      }
      // Lag and its gauge only once the whole refresh has succeeded, so a rolled-back partition
      // never reports a lag of 0 while it is not read (code review 4, MINOR 1).
      for (TopicPartition p : added) {
        lag.put(p, 0L);
        assignedHook.accept(p);
      }
      return now;
    } catch (RuntimeException unavailable) {
      refreshMisses.incrementAndGet();
      LOG.warn(
          "new partitions could not be assigned; tried again at the next refresh", unavailable);
      consumer.assign(assigned);
      return assigned;
    }
  }

  /** Fixes a lazy seek by asking for the position, and starts its lag at zero (point 5). */
  private void positioned(
      org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer, TopicPartition p) {
    resolvePosition(consumer, p);
    lag.put(p, 0L);
    assignedHook.accept(p);
  }

  private static void resolvePosition(
      org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer, TopicPartition p) {
    // The seek is lazy: position() resolves it, with a bounded broker request (ListOffsets).
    // Kafka never answers a negative position; the check guards the contract, not a case seen.
    if (consumer.position(p, POSITION_TIMEOUT) < 0) {
      throw new IllegalStateException("no position for " + p);
    }
  }

  void measureLag(
      org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
      Set<TopicPartition> assigned) {
    // currentLag reads what the last fetch reported and makes no request, so a slow broker can
    // never turn a metric into a consumer failure (code review 2, MAJOR 2). A partition with no
    // fetch yet keeps its last value.
    for (TopicPartition p : assigned) {
      try {
        consumer.currentLag(p).ifPresent(value -> lag.put(p, value));
      } catch (RuntimeException unknown) {
        lagMisses.incrementAndGet();
      }
    }
  }

  long refreshMisses() {
    return refreshMisses.get();
  }

  long lagMisses() {
    return lagMisses.get();
  }

  /**
   * Routes one record (point 5's steps 1 to 5).
   *
   * @param topic the topic
   * @param value the envelope
   * @param createdMillis its CreateTime
   */
  void route(String topic, byte[] value, long createdMillis) {
    Optional<AlertFrames.Routed> routed = frames.route(topic, value, createdMillis);
    if (routed.isEmpty()) {
      invalid.incrementAndGet();
      return;
    }
    AlertFrames.Routed r = routed.get();
    if (r.assignee() != null) {
      Optional<Map<String, Object>> name = names.cached(r.institutionId(), r.assignee());
      if (name.isEmpty()) {
        names.resolve(r.institutionId(), r.assignee(), reference -> deliver(r, reference));
        return;
      }
      deliver(r, name.get());
      return;
    }
    deliver(r, null);
  }

  @SuppressWarnings("unchecked")
  private void deliver(AlertFrames.Routed r, Map<String, Object> assignee) {
    if (assignee != null) {
      Map<String, Object> body = new java.util.LinkedHashMap<>(r.body());
      Map<String, Object> alert =
          new java.util.LinkedHashMap<>((Map<String, Object>) body.get("alert"));
      alert.put("assigned_analyst", assignee);
      body.put("alert", alert);
      deliver(r.institutionId(), r.visibility(), body, r.createdMillis());
      return;
    }
    deliver(r.institutionId(), r.visibility(), r.body(), r.createdMillis());
  }

  private void deliver(
      java.util.UUID institutionId,
      Visibility visibility,
      Map<String, Object> body,
      long createdMillis) {
    seen.accept(institutionId);
    Outbound.Frame frame =
        StompFrames.event(StompEndpoint.ALERTS, AlertFrames.json(body), createdMillis);
    for (RealtimeSession session : registry.sessionsOf(institutionId)) {
      Binding binding = session.binding();
      if (binding == null
          || !session.subscribed(StompEndpoint.ALERTS)
          || !visibility.admits(binding)) {
        continue;
      }
      if (!session.deliver(frame)
          && session.fail(StompFrames.Problem.OVERFLOW, "too far behind", null)) {
        overflows.incrementAndGet();
      }
    }
    // The instance budget is enforced as each record is delivered, not only by the sweep, so the
    // counted total passes it by at most one record's frames per delivering thread (code review
    // 2, MINOR 6; code review 4, MINOR 3).
    for (RealtimeSession largest : registry.toShed()) {
      if (largest.fail(
          StompFrames.Problem.OVERFLOW, "the instance's outbound budget was exceeded", null)) {
        overflows.incrementAndGet();
      }
    }
  }

  /**
   * Whether the consumer is positioned and has polled recently (point 5).
   *
   * @return true when healthy
   */
  boolean healthy() {
    return ready && nanoTime.getAsLong() - lastPollNanos < settings.consumerStall().toNanos();
  }

  Map<TopicPartition, Long> lag() {
    return lag;
  }

  long invalid() {
    return invalid.get();
  }

  long overflows() {
    return overflows.get();
  }

  /**
   * The consumer's properties: no group, no commits, a short fetch wait (point 11).
   *
   * @param bootstrapServers the brokers
   * @return the properties
   */
  static java.util.Properties properties(String bootstrapServers) {
    java.util.Properties p = new java.util.Properties();
    p.put("bootstrap.servers", bootstrapServers);
    p.put("client.id", "websocket-fanout");
    p.put("enable.auto.commit", false);
    p.put("auto.offset.reset", "latest");
    p.put("fetch.max.wait.ms", 50);
    p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    return p;
  }

  /**
   * A real consumer.
   *
   * @param bootstrapServers the brokers
   * @return a supplier of it
   */
  static Supplier<org.apache.kafka.clients.consumer.Consumer<String, byte[]>> kafka(
      String bootstrapServers) {
    return () -> new KafkaConsumer<>(properties(bootstrapServers));
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
  }
}
