package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** A socket that records what the server writes, for the STOMP rules' unit tests. */
final class FakeSocket implements WebSocketSession {

  private final String id = UUID.randomUUID().toString();
  private final Map<String, Object> attributes = new ConcurrentHashMap<>();
  private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
  private volatile boolean open = true;
  private volatile java.util.concurrent.CountDownLatch stalled;

  FakeSocket(String address) {
    attributes.put(StompEndpoint.addressAttribute(), address);
  }

  /**
   * The next frame written, skipping heart-beats.
   *
   * @return the frame, or null after two seconds
   */
  String next() throws InterruptedException {
    while (true) {
      String frame = sent.poll(2, TimeUnit.SECONDS);
      if (frame == null || !frame.equals("\n")) {
        return frame;
      }
    }
  }

  /** Makes every write wait until {@link #release()}: a reader on a stalled link. */
  void stall() {
    stalled = new java.util.concurrent.CountDownLatch(1);
  }

  void release() {
    stalled.countDown();
  }

  /** Waits for the server to close the socket. */
  boolean awaitClosed() throws InterruptedException {
    for (int i = 0; i < 200 && open; i++) {
      Thread.sleep(10);
    }
    return !open;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public URI getUri() {
    return URI.create("ws://localhost/api/v1/realtime");
  }

  @Override
  public HttpHeaders getHandshakeHeaders() {
    return new HttpHeaders();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public Principal getPrincipal() {
    return null;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return null;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return null;
  }

  @Override
  public String getAcceptedProtocol() {
    return "v12.stomp";
  }

  @Override
  public void setTextMessageSizeLimit(int messageSizeLimit) {}

  @Override
  public int getTextMessageSizeLimit() {
    return 16 * 1024;
  }

  @Override
  public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

  @Override
  public int getBinaryMessageSizeLimit() {
    return 0;
  }

  @Override
  public List<WebSocketExtension> getExtensions() {
    return List.of();
  }

  @Override
  public void sendMessage(WebSocketMessage<?> message) {
    java.util.concurrent.CountDownLatch gate = stalled;
    if (gate != null) {
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    sent.add(((TextMessage) message).getPayload());
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
  }

  @Override
  public void close(CloseStatus status) {
    open = false;
  }
}
