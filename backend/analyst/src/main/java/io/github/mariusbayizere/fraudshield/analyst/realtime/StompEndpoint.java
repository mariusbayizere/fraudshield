package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * The STOMP 1.2 server of the real-time channel (ADR 0115 points 2, 3 and 8). It is a plain
 * WebSocket handler: there is no broker, no shared destination and no user-destination translation,
 * and it writes every frame to its own session.
 *
 * <p>Spring Security's CSRF check on CONNECT does not run here, by design (ADR 0115 point 2): the
 * credential is the CONNECT frame's bearer token, which a cross-site page cannot write, and this
 * handler is not behind Spring's STOMP security interceptor. A test asserts that a CONNECT with a
 * valid token and no CSRF token is accepted.
 */
public final class StompEndpoint extends AbstractWebSocketHandler implements SubProtocolCapable {

  /** The alert destination. */
  public static final String ALERTS = "/user/queue/alerts";

  /** The figures destination. */
  public static final String FIGURES = "/user/queue/figures";

  /** The only destination a client may send to. */
  public static final String REAUTH = "/app/session.reauth";

  /** The roles admitted at CONNECT: those of {@code listAlerts} and {@code getDashboardStats}. */
  static final Set<StaffRole> ROLES =
      EnumSet.of(StaffRole.ANALYST, StaffRole.SENIOR_ANALYST, StaffRole.RISK_OFFICER);

  private static final String ADDRESS = "fs.realtime.address";

  /** Tomcat's per-session bound on a blocking send, which its close frame also uses. */
  static final String BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";

  /** A send, or the close, waits at most this long on a stalled link (ASSUMED). */
  static final Long BLOCKING_SEND_MILLIS = 2_000L;

  private final SessionRegistry registry;
  private final SessionGuard guard;
  private final JwtDecoder tokens;
  private final RealtimeSettings settings;
  private final InstanceBudget budget;
  private final RealtimeSession.Written written;
  private final Subscribed subscribed;
  private final LongSupplier nanoTime;

  /** Told after a session subscribes or ends, for the figures (point 6.3). */
  interface Subscribed {
    /**
     * A session subscribed to a destination.
     *
     * @param session the session
     * @param destination the destination
     */
    void subscribed(RealtimeSession session, String destination);

    /**
     * A bound session ended.
     *
     * @param binding its binding
     */
    void ended(Binding binding);

    /** Does nothing. */
    Subscribed NONE =
        new Subscribed() {
          @Override
          public void subscribed(RealtimeSession session, String destination) {}

          @Override
          public void ended(Binding binding) {}
        };
  }

  StompEndpoint(
      SessionRegistry registry,
      SessionGuard guard,
      JwtDecoder tokens,
      RealtimeSettings settings,
      InstanceBudget budget,
      RealtimeSession.Written written,
      Subscribed subscribed,
      LongSupplier nanoTime) {
    this.registry = registry;
    this.guard = guard;
    this.tokens = tokens;
    this.settings = settings;
    this.budget = budget;
    this.written = written;
    this.subscribed = subscribed;
    this.nanoTime = nanoTime;
  }

  /**
   * The attribute name under which the handshake records the client address.
   *
   * @return the name
   */
  static String addressAttribute() {
    return ADDRESS;
  }

  @Override
  public List<String> getSubProtocols() {
    return List.of("v12.stomp");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) {
    // Bounds every blocking send and the close frame, so a stalled link holds neither a writer nor
    // a revoked session's close for the container's default 20 s (code review 3, MINOR 2).
    if (socket instanceof NativeWebSocketSession nativeSocket
        && nativeSocket.getNativeSession() instanceof jakarta.websocket.Session container) {
      container.getUserProperties().put(BLOCKING_SEND_TIMEOUT, BLOCKING_SEND_MILLIS);
    }
    RealtimeSession session =
        new RealtimeSession(
            socket,
            (String) socket.getAttributes().get(ADDRESS),
            nanoTime.getAsLong(),
            new Outbound(settings.queueFrames(), settings.queueBytes(), budget),
            written);
    socket.getAttributes().put(RealtimeSession.class.getName(), session);
    registry.opened(session);
    session.start();
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    RealtimeSession session = session(socket);
    if (session == null) {
      return;
    }
    Binding binding = session.binding();
    registry.closed(session);
    session.closeQuietly();
    if (binding != null) {
      subscribed.ended(binding);
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
    RealtimeSession session = session(socket);
    if (session != null) {
      session.fail(StompFrames.Problem.VALIDATION, "frames are text", null);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    RealtimeSession session = session(socket);
    if (session == null) {
      return;
    }
    session.received(nanoTime.getAsLong());
    byte[] payload = message.getPayload().getBytes(StandardCharsets.UTF_8);
    if (payload.length > settings.inboundFrameBytes()) {
      session.fail(StompFrames.Problem.VALIDATION, "frame too large", null);
      return;
    }
    List<Message<byte[]>> frames;
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      frames = new StompDecoder().decode(buffer);
      if (buffer.hasRemaining()) {
        throw new IllegalArgumentException("an incomplete frame");
      }
    } catch (RuntimeException unreadable) {
      session.fail(
          session.authenticated()
              ? StompFrames.Problem.FORBIDDEN
              : StompFrames.Problem.UNAUTHORIZED,
          "the frame could not be read",
          null);
      return;
    }
    for (Message<byte[]> frame : frames) {
      StompHeaderAccessor headers = StompHeaderAccessor.wrap(frame);
      if (headers.isHeartbeat() || headers.getCommand() == null) {
        continue;
      }
      if (!handle(session, headers)) {
        return;
      }
    }
  }

  /** Handles one frame; false once the session has been failed. */
  private boolean handle(RealtimeSession session, StompHeaderAccessor frame) {
    StompCommand command = frame.getCommand();
    String receipt = frame.getReceipt();
    if (!session.authenticated()) {
      if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
        return connect(session, frame);
      }
      session.fail(StompFrames.Problem.UNAUTHORIZED, "CONNECT first", receipt);
      return false;
    }
    switch (command) {
      case CONNECT, STOMP -> {
        session.fail(StompFrames.Problem.FORBIDDEN, "already connected", receipt);
        return false;
      }
      case SUBSCRIBE -> {
        return subscribe(session, frame);
      }
      case UNSUBSCRIBE -> {
        String id = frame.getSubscriptionId();
        if (id != null) {
          session.unsubscribe(id);
        }
        return receipt(session, receipt);
      }
      case SEND -> {
        if (!REAUTH.equals(frame.getDestination())) {
          session.fail(StompFrames.Problem.FORBIDDEN, "not a destination", receipt);
          return false;
        }
        return reauthenticate(session, frame);
      }
      case DISCONNECT -> {
        if (receipt != null) {
          session.control(StompFrames.receipt(receipt));
        }
        session.closeAfterQueued();
        return false;
      }
      default -> {
        session.fail(StompFrames.Problem.FORBIDDEN, "not supported", receipt);
        return false;
      }
    }
  }

  private boolean connect(RealtimeSession session, StompHeaderAccessor frame) {
    Set<String> versions = frame.getAcceptVersion();
    // A CONNECT without accept-version is STOMP 1.0's; only 1.2 is spoken (point 1).
    if (versions == null || !versions.contains("1.2")) {
      session.fail(StompFrames.Problem.VALIDATION, "STOMP 1.2 is required", null);
      return false;
    }
    long[] beats = frame.getHeartbeat();
    long cap = settings.maxClientHeartBeat().toMillis();
    if (beats != null && (beats[0] > cap || beats[1] > cap)) {
      // Refused rather than capped: a client beating less often than the cap would be closed for
      // silence at the edge of every beat (code review 2, MINOR 7).
      session.fail(StompFrames.Problem.VALIDATION, "heart-beat above " + cap + " ms", null);
      return false;
    }
    if (!guard.open()) {
      // The channel is closed while Redis is down or the consumer is not positioned (points 3
      // and 5); a socket admitted just before the closure must not bind.
      session.fail(StompFrames.Problem.UNAVAILABLE, "real-time channel unavailable", null);
      return false;
    }
    Optional<Binding> binding = authenticate(session, frame);
    if (binding.isEmpty()) {
      return false;
    }
    if (!ROLES.contains(binding.get().role())) {
      session.fail(StompFrames.Problem.FORBIDDEN, "role not admitted", null);
      return false;
    }
    List<RealtimeSession> evicted = new ArrayList<>();
    Optional<SessionRegistry.ConnectRefusal> refused =
        registry.bind(session, binding.get(), evicted);
    if (refused.isPresent()) {
      switch (refused.get()) {
        case CLOSED -> session.closeQuietly();
        case RATE_LIMITED ->
            session.fail(StompFrames.Problem.RATE_LIMITED, "too many connections", null);
        case INSTANCE_FULL, PAIRS_FULL ->
            session.fail(StompFrames.Problem.UNAVAILABLE, "this instance is full", null);
        default -> throw new IllegalStateException("unknown refusal");
      }
      return false;
    }
    for (RealtimeSession oldest : evicted) {
      oldest.fail(StompFrames.Problem.RATE_LIMITED, "too many sessions for this user", null);
    }
    long offer = settings.heartBeat().toMillis();
    long clientSends = beats == null ? 0 : beats[0];
    long clientWants = beats == null ? 0 : beats[1];
    long sendEvery = clientWants == 0 ? 0 : Math.max(offer, clientWants);
    long expectEvery = clientSends == 0 ? 0 : Math.max(offer, clientSends);
    session.heartBeats(sendEvery, expectEvery);
    session.control(StompFrames.connected(offer, offer));
    guard.present(binding.get());
    return true;
  }

  private boolean subscribe(RealtimeSession session, StompHeaderAccessor frame) {
    String destination = frame.getDestination();
    String receipt = frame.getReceipt();
    if (!ALERTS.equals(destination) && !FIGURES.equals(destination)) {
      session.fail(StompFrames.Problem.FORBIDDEN, "not a destination", receipt);
      return false;
    }
    String id = frame.getSubscriptionId();
    if (id == null || id.isBlank()) {
      session.fail(StompFrames.Problem.VALIDATION, "SUBSCRIBE needs an id", receipt);
      return false;
    }
    // Registered with the router before the RECEIPT is queued, so every event routed after the
    // RECEIPT reaches the session (point 7).
    session.subscribe(destination, id);
    boolean ok = receipt(session, receipt);
    subscribed.subscribed(session, destination);
    return ok;
  }

  private boolean reauthenticate(RealtimeSession session, StompHeaderAccessor frame) {
    String receipt = frame.getReceipt();
    Optional<Binding> fresh = authenticate(session, frame);
    if (fresh.isEmpty()) {
      return false;
    }
    StaffClaims was = session.binding().claims();
    StaffClaims now = fresh.get().claims();
    if (!was.userId().equals(now.userId())
        || !was.institutionId().equals(now.institutionId())
        || was.role() != now.role()) {
      session.fail(StompFrames.Problem.FORBIDDEN, "identity changed", receipt);
      return false;
    }
    if (!was.sessionId().equals(now.sessionId())) {
      session.fail(StompFrames.Problem.FORBIDDEN, "session changed", receipt);
      return false;
    }
    registry.rebind(session, fresh.get());
    return receipt(session, receipt);
  }

  /** Validates the frame's bearer token exactly as on HTTP (point 2). */
  private Optional<Binding> authenticate(RealtimeSession session, StompHeaderAccessor frame) {
    String header = frame.getFirstNativeHeader("Authorization");
    String receipt = frame.getReceipt();
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      session.fail(StompFrames.Problem.UNAUTHORIZED, "a bearer token is required", receipt);
      return Optional.empty();
    }
    try {
      Jwt jwt = tokens.decode(header.substring(7).strip());
      StaffClaims claims = AccessTokens.claims(jwt);
      Instant expiresAt = jwt.getExpiresAt();
      if (expiresAt == null) {
        throw new IllegalArgumentException("no exp");
      }
      return Optional.of(new Binding(claims, expiresAt));
    } catch (JwtException | IllegalArgumentException | ClassCastException e) {
      session.fail(StompFrames.Problem.UNAUTHORIZED, "the token is not valid", receipt);
      return Optional.empty();
    }
  }

  private static boolean receipt(RealtimeSession session, String receipt) {
    if (receipt != null && !session.control(StompFrames.receipt(receipt))) {
      session.fail(StompFrames.Problem.OVERFLOW, "too far behind", null);
      return false;
    }
    return true;
  }

  private static RealtimeSession session(WebSocketSession socket) {
    return (RealtimeSession) socket.getAttributes().get(RealtimeSession.class.getName());
  }
}
