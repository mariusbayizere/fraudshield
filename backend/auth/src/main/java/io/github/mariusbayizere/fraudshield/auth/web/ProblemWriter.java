package io.github.mariusbayizere.fraudshield.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Renders problems as {@code application/problem+json} (RFC 9457, ADR 0011). */
public final class ProblemWriter {

  /** Media type of problem responses. */
  public static final String MEDIA_TYPE = "application/problem+json";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ProblemWriter() {}

  /**
   * The problem body.
   *
   * @param problem the problem
   * @param request the request (for the correlation ID)
   * @return the members in contract order
   */
  public static Map<String, Object> body(ProblemException problem, HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", problem.type());
    body.put("title", problem.title());
    body.put("status", problem.status());
    body.put("detail", problem.getMessage());
    body.put("correlation_id", CorrelationIdFilter.of(request).toString());
    if (!problem.errors().isEmpty()) {
      List<Map<String, String>> errors =
          problem.errors().stream()
              .map(e -> Map.of("field", e.field(), "code", e.code(), "message", e.message()))
              .toList();
      body.put("errors", errors);
    }
    body.putAll(problem.extensions());
    return body;
  }

  /**
   * Writes a problem directly to a servlet response (used by security filters, which run before
   * Spring MVC).
   *
   * @param problem the problem
   * @param request the request
   * @param response the response
   * @throws IOException if writing fails
   */
  public static void write(
      ProblemException problem, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(problem.status());
    response.setContentType(MEDIA_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-store");
    if (problem.retryAfterSeconds() != null) {
      response.setHeader("Retry-After", problem.retryAfterSeconds().toString());
    }
    if (problem.status() == HttpServletResponse.SC_UNAUTHORIZED) {
      response.setHeader("WWW-Authenticate", "Bearer");
    }
    response.getOutputStream().write(JSON.writeValueAsBytes(body(problem, request)));
  }
}
