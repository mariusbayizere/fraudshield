package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The STOMP 1.2 frames the server writes (ADR 0115 points 2 and 9). Inbound frames are decoded by
 * Spring's {@code StompDecoder}; outbound ones are small enough to write here, so a MESSAGE can
 * reuse one event body for every recipient and add only its own subscription id.
 */
final class StompFrames {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final char NUL = '\0';

  /** The problems an ERROR frame may carry: the {@code RealtimeError} response's types. */
  enum Problem {
    UNAUTHORIZED("unauthorized", 401, "Unauthorized"),
    FORBIDDEN("forbidden", 403, "Forbidden"),
    VALIDATION("validation", 400, "Invalid frame"),
    RATE_LIMITED("rate-limited", 429, "Too many sessions or connections"),
    OVERFLOW("realtime-overflow", 503, "Too far behind"),
    UNAVAILABLE("service-unavailable", 503, "Service unavailable");

    private final String code;
    private final int status;
    private final String title;

    Problem(String code, int status, String title) {
      this.code = code;
      this.status = status;
      this.title = title;
    }
  }

  private StompFrames() {}

  static String connected(long sendEvery, long expectEvery) {
    return "CONNECTED\nversion:1.2\nheart-beat:" + sendEvery + "," + expectEvery + "\n\n" + NUL;
  }

  static String receipt(String receiptId) {
    return "RECEIPT\nreceipt-id:" + escape(receiptId) + "\n\n" + NUL;
  }

  /**
   * An ERROR frame whose body is a Problem document (the {@code RealtimeError} response).
   *
   * @param problem the problem
   * @param detail the detail
   * @param receiptId the receipt of the frame that caused it, or null
   * @return the frame
   */
  static String error(Problem problem, String detail, String receiptId) {
    String json = problem(problem.code, problem.status, problem.title, detail);
    StringBuilder frame = new StringBuilder("ERROR\nmessage:").append(escape(problem.title));
    if (receiptId != null) {
      frame.append("\nreceipt-id:").append(escape(receiptId));
    }
    return frame
        .append("\ncontent-type:application/problem+json\ncontent-length:")
        .append(json.getBytes(StandardCharsets.UTF_8).length)
        .append("\n\n")
        .append(json)
        .append(NUL)
        .toString();
  }

  /**
   * A Problem document (RFC 9457, the contract's {@code Problem}).
   *
   * @param code the catalogue code, without its prefix
   * @param status the status
   * @param title the title
   * @param detail the detail
   * @return the JSON
   */
  static String problem(String code, int status, String title, String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "urn:fraudshield:problem:" + code);
    body.put("title", title);
    body.put("status", status);
    body.put("detail", detail);
    body.put("correlation_id", UUID.randomUUID().toString());
    return JSON.writeValueAsString(body);
  }

  /**
   * A MESSAGE frame around a shared event body.
   *
   * @param destination the destination
   * @param subscriptionId this session's subscription id
   * @param messageId a per-session message id
   * @param body the shared JSON body
   * @param bodyBytes its size in bytes
   * @return the frame
   */
  static String message(
      String destination, String subscriptionId, long messageId, String body, int bodyBytes) {
    return "MESSAGE\ndestination:"
        + escape(destination)
        + "\nsubscription:"
        + escape(subscriptionId)
        + "\nmessage-id:"
        + messageId
        + "\ncontent-type:application/json\ncontent-length:"
        + bodyBytes
        + "\n\n"
        + body
        + NUL;
  }

  /** STOMP 1.2 header escaping, for every frame but CONNECT and CONNECTED. */
  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case ':' -> out.append("\\c");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * A shared event body as a frame for the queue.
   *
   * @param destination the destination
   * @param body the JSON body
   * @param createdMillis its Kafka record's CreateTime
   * @return the queued frame
   */
  static Outbound.Frame event(String destination, String body, long createdMillis) {
    return new Outbound.Frame(
        destination, body, body.getBytes(StandardCharsets.UTF_8).length, createdMillis);
  }

  /**
   * A control frame, already encoded for one session, as a frame for the queue.
   *
   * @param frame the encoded frame
   * @return the queued frame
   */
  static Outbound.Frame control(String frame) {
    return new Outbound.Frame(null, frame, frame.getBytes(StandardCharsets.UTF_8).length, 0);
  }

  static String json(Object value) {
    return JSON.writeValueAsString(value);
  }
}
