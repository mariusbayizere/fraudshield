package io.github.mariusbayizere.fraudshield.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (H.1: correlation ID on every error), returned in {@code
 * X-Correlation-ID}, put in the log context and recorded with audit events. A client-supplied
 * {@code X-Correlation-ID} is accepted only if it is a UUID, so it cannot inject into logs.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

  /** Header carrying the ID. */
  public static final String HEADER = "X-Correlation-ID";

  /** Request attribute and MDC key. */
  public static final String ATTRIBUTE = "correlation_id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID id = parse(request.getHeader(HEADER));
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id.toString());
    MDC.put(ATTRIBUTE, id.toString());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ATTRIBUTE);
    }
  }

  private static UUID parse(String header) {
    if (header != null) {
      try {
        return UUID.fromString(header);
      } catch (IllegalArgumentException ignored) {
        // Not a UUID: replaced by a fresh one.
      }
    }
    return UUID.randomUUID();
  }

  /**
   * The request's correlation ID.
   *
   * @param request the request
   * @return the ID (a fresh one if the filter did not run)
   */
  public static UUID of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof UUID id ? id : UUID.randomUUID();
  }
}
