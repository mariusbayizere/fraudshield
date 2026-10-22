package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One socket of the real-time channel (ADR 0115). Every write to the socket is made by the
 * session's own writer, a virtual thread draining its bounded {@link Outbound} queue, so neither
 * the Kafka consumer nor any other thread ever waits on a slow socket (point 8). The writer also
 * sends the negotiated heart-beats.
 */
final class RealtimeSession {

  /** Told of every event frame written, for the push histogram (point 11). */
  @FunctionalInterface
  interface Written {
    /**
     * An event frame reached the socket.
     *
     * @param frame the frame
     */
    void written(Outbound.Frame frame);
  }

  private static final Logger LOG = LoggerFactory.getLogger(RealtimeSession.class);

  private final WebSocketSession socket;
  private final String address;
  private final long openedNanos;
  private final Outbound outbound;
  private final Written written;
  private final Map<String, String> subscriptions = new ConcurrentHashMap<>();
  private final AtomicLong messageIds = new AtomicLong();
  private volatile Binding binding;
  private volatile long sendEveryMillis;
  private volatile long expectEveryMillis;
  private volatile long lastInboundNanos;
  private volatile long boundNanos;

  RealtimeSession(
      WebSocketSession socket,
      String address,
      long openedNanos,
      Outbound outbound,
      Written written) {
    this.socket = Objects.requireNonNull(socket, "socket");
    this.address = address;
    this.openedNanos = openedNanos;
    this.lastInboundNanos = openedNanos;
    this.outbound = outbound;
    this.written = written;
  }

  /** Starts the writer. */
  void start() {
    Thread.ofVirtual().name("fs-realtime-writer-" + socket.getId()).start(this::write);
  }

  private void write() {
    try {
      while (true) {
        long idle = sendEveryMillis > 0 ? sendEveryMillis : 1_000;
        Outbound.Frame frame = outbound.take(idle, TimeUnit.MILLISECONDS);
        if (frame == null) {
          return;
        }
        if (frame == Outbound.IDLE) {
          if (sendEveryMillis > 0 && socket.isOpen()) {
            socket.sendMessage(new TextMessage("\n"));
          }
          continue;
        }
        if (frame == Outbound.CLOSE) {
          socket.close(CloseStatus.NORMAL);
          outbound.close();
          return;
        }
        if (frame.destination() == null) {
          socket.sendMessage(new TextMessage(frame.body()));
          if (frame.body().startsWith("ERROR")) {
            // STOMP 1.2: the server closes the connection after an ERROR.
            socket.close(CloseStatus.NORMAL);
            return;
          }
          continue;
        }
        String subscription = subscriptions.get(frame.destination());
        if (subscription != null) {
          socket.sendMessage(
              new TextMessage(
                  StompFrames.message(
                      frame.destination(),
                      subscription,
                      messageIds.incrementAndGet(),
                      frame.body(),
                      frame.bytes())));
          written.written(frame);
        }
      }
    } catch (IOException | IllegalStateException closed) {
      LOG.debug("a real-time socket closed while writing", closed);
      closeQuietly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closeQuietly();
    }
  }

  /**
   * Queues an event or a control frame, never blocking.
   *
   * @param frame the frame
   * @return false when the queue is full or closed
   */
  boolean deliver(Outbound.Frame frame) {
    return outbound.offer(frame);
  }

  /**
   * Sends one control frame.
   *
   * @param frame the encoded frame
   * @return false when the queue is full or closed
   */
  boolean control(String frame) {
    return outbound.offer(StompFrames.control(frame));
  }

  /**
   * Ends the session with an ERROR frame; what is queued is dropped (points 3 and 8).
   *
   * @param problem the problem
   * @param detail its detail
   * @param receiptId the receipt of the frame that caused it, or null
   */
  void fail(StompFrames.Problem problem, String detail, String receiptId) {
    // A session already failing keeps its first ERROR; closing it here could drop that frame.
    outbound.fail(StompFrames.control(StompFrames.error(problem, detail, receiptId)));
  }

  /** Closes the socket once what is already queued has been written (DISCONNECT). */
  void closeAfterQueued() {
    if (!outbound.offer(Outbound.CLOSE)) {
      closeQuietly();
    }
  }

  /** Closes the socket without a frame. */
  void closeQuietly() {
    outbound.close();
    try {
      socket.close(CloseStatus.NORMAL);
    } catch (IOException | IllegalStateException ignored) {
      // Already closed.
    }
  }

  void subscribe(String destination, String id) {
    subscriptions.put(destination, id);
  }

  boolean subscribed(String destination) {
    return subscriptions.containsKey(destination);
  }

  void unsubscribe(String id) {
    subscriptions.values().removeIf(id::equals);
  }

  void heartBeats(long sendEvery, long expectEvery) {
    this.sendEveryMillis = sendEvery;
    this.expectEveryMillis = expectEvery;
  }

  long expectEveryMillis() {
    return expectEveryMillis;
  }

  void received(long nanos) {
    lastInboundNanos = nanos;
  }

  long lastInboundNanos() {
    return lastInboundNanos;
  }

  void bind(Binding binding, long nanos) {
    this.binding = binding;
    if (boundNanos == 0) {
      boundNanos = nanos;
    }
  }

  Binding binding() {
    return binding;
  }

  long boundNanos() {
    return boundNanos;
  }

  boolean authenticated() {
    return binding != null;
  }

  String id() {
    return socket.getId();
  }

  String address() {
    return address;
  }

  long openedNanos() {
    return openedNanos;
  }

  long queuedBytes() {
    return outbound.bytes();
  }
}
