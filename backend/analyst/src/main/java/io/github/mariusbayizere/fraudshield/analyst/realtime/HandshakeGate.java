package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Admits or refuses a handshake before the upgrade (ADR 0115 points 2, 3, 5 and 8). It
 * authenticates no one, so it answers every caller alike: 426 for anything that is not a STOMP
 * WebSocket upgrade, 403 for an origin not allowed, 503 while the instance is not ready, full, or
 * Redis is down, and 429 for an address over its limits. Every refusal is a problem document.
 */
final class HandshakeGate implements HandshakeInterceptor {

  private final SessionRegistry registry;
  private final SessionGuard guard;
  private final List<String> allowedOrigins;

  HandshakeGate(SessionRegistry registry, SessionGuard guard, List<String> allowedOrigins) {
    this.registry = registry;
    this.guard = guard;
    this.allowedOrigins = List.copyOf(allowedOrigins);
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes)
      throws IOException {
    if (!isStompUpgrade(request)) {
      refuse(
          response,
          426,
          "upgrade-required",
          "Upgrade required",
          "a STOMP 1.2 WebSocket upgrade is required");
      return false;
    }
    if (!originAllowed(request)) {
      refuse(response, 403, "forbidden", "Forbidden", "origin not allowed");
      return false;
    }
    if (!guard.open()) {
      unavailable(response, "the real-time channel is not available on this instance");
      return false;
    }
    String address = address(request);
    Optional<SessionRegistry.Refusal> refusal = registry.admit(address);
    if (refusal.isPresent()) {
      switch (refusal.get()) {
        case INSTANCE_FULL, PENDING_FULL -> unavailable(response, "this instance is full");
        case ADDRESS_PENDING, ADDRESS_RATE ->
            refuse(response, 429, "rate-limited", "Too many requests", "too many handshakes");
        default -> throw new IllegalStateException("unknown refusal");
      }
      return false;
    }
    attributes.put(StompEndpoint.addressAttribute(), address);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {
    // Nothing to undo: the socket is counted only once it is established.
  }

  static boolean isStompUpgrade(ServerHttpRequest request) {
    HttpHeaders headers = request.getHeaders();
    String upgrade = headers.getFirst(HttpHeaders.UPGRADE);
    if (upgrade == null || !asciiEqualsIgnoreCase(upgrade.strip(), "websocket")) {
      return false;
    }
    List<String> protocols = headers.get("Sec-WebSocket-Protocol");
    if (protocols == null) {
      return false;
    }
    for (String line : protocols) {
      for (String p : line.split(",")) {
        if ("v12.stomp".equals(p.strip())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean originAllowed(ServerHttpRequest request) {
    String origin = request.getHeaders().getOrigin();
    if (origin == null) {
      return false;
    }
    if (!allowedOrigins.isEmpty()) {
      return allowedOrigins.contains(origin);
    }
    // The default is the API's own origin (ADR 0115 point 2, ASSUMED).
    URI uri = request.getURI();
    String own =
        uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    return asciiEqualsIgnoreCase(own, origin)
        || asciiEqualsIgnoreCase(ownWithoutDefaultPort(uri), origin);
  }

  /** Header tokens and origins are ASCII: compare them without locale-dependent case mapping. */
  static boolean asciiEqualsIgnoreCase(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      char x = a.charAt(i);
      char y = b.charAt(i);
      if (x == y) {
        continue;
      }
      char lower = (char) (x | 0x20);
      if (lower < 'a' || lower > 'z' || lower != (char) (y | 0x20)) {
        return false;
      }
    }
    return true;
  }

  private static String ownWithoutDefaultPort(URI uri) {
    return uri.getScheme() + "://" + uri.getHost();
  }

  /** The forwarded client address, as {@code Requests} resolves it (point 8). */
  private static String address(ServerHttpRequest request) {
    if (request instanceof ServletServerHttpRequest servlet) {
      return servlet.getServletRequest().getRemoteAddr();
    }
    return request.getRemoteAddress() == null
        ? "unknown"
        : request.getRemoteAddress().getAddress().getHostAddress();
  }

  private static void unavailable(ServerHttpResponse response, String detail) throws IOException {
    response.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
    refuse(response, 503, "service-unavailable", "Service unavailable", detail);
  }

  static void refuse(
      ServerHttpResponse response, int status, String code, String title, String detail)
      throws IOException {
    response.setStatusCode(HttpStatus.valueOf(status));
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    response
        .getBody()
        .write(StompFrames.problem(code, status, title, detail).getBytes(StandardCharsets.UTF_8));
    response.flush();
  }

  /**
   * Replaces the framework's own refusals of a bad upgrade (400, or 426 without a body) with the
   * catalogue's {@code upgrade-required} problem (ADR 0115 point 2).
   */
  static final class Handshakes extends DefaultHandshakeHandler {

    @Override
    protected void handleInvalidUpgradeHeader(
        ServerHttpRequest request, ServerHttpResponse response) throws IOException {
      upgradeRequired(response);
    }

    @Override
    protected void handleInvalidConnectHeader(
        ServerHttpRequest request, ServerHttpResponse response) throws IOException {
      upgradeRequired(response);
    }

    @Override
    protected void handleWebSocketVersionNotSupported(
        ServerHttpRequest request, ServerHttpResponse response) {
      response.getHeaders().set("Sec-WebSocket-Version", "13");
      try {
        upgradeRequired(response);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }

    private static void upgradeRequired(ServerHttpResponse response) throws IOException {
      refuse(
          response,
          426,
          "upgrade-required",
          "Upgrade required",
          "a STOMP 1.2 WebSocket upgrade is required");
    }
  }
}
