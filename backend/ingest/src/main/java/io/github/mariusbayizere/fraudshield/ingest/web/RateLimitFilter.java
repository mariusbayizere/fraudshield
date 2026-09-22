package io.github.mariusbayizere.fraudshield.ingest.web;

import io.github.mariusbayizere.fraudshield.ingest.application.Problems;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds each API key to its request budget on the machine paths (E.1).
 *
 * <p>It runs after {@link ApiKeyFilter}, so the budget belongs to the authenticated key rather than
 * to an address: an unauthenticated request is refused before it costs anything. A refused request
 * is 429 {@code rate-limited} with {@code Retry-After} in whole seconds, as the contract says.
 *
 * <p>Every answer carries {@code RateLimit-Limit} and {@code RateLimit-Remaining}, and an answer
 * the shared limiter could not give carries {@code RateLimit-Degraded: true}: while Redis is down
 * each instance holds the budget on its own, so the limit is per instance. An outage weakens the
 * control and says so; it never switches it off silently.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

  /**
   * Says the budget was held by this instance alone, because the shared limiter was unreachable.
   */
  public static final String DEGRADED_HEADER = "RateLimit-Degraded";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RateLimiter limiter;

  /**
   * Creates the filter.
   *
   * @param limiter the limiter
   */
  public RateLimitFilter(RateLimiter limiter) {
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return ApiKeyFilter.PROTECTED.stream().noneMatch(path::startsWith);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Object principal = request.getAttribute(ApiKeyFilter.PRINCIPAL);
    if (!(principal instanceof ApiPrincipal key)) {
      // Unauthenticated: ApiKeyFilter has already refused it, or will.
      chain.doFilter(request, response);
      return;
    }
    RateLimiter.Permit permit = limiter.take(key.apiKeyId());
    response.setHeader("RateLimit-Limit", Integer.toString(permit.limit()));
    response.setHeader("RateLimit-Remaining", Integer.toString(permit.remaining()));
    if (permit.degraded()) {
      response.setHeader(DEGRADED_HEADER, "true");
    }
    if (permit.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    final byte[] body =
        JSON.writeValueAsBytes(
            Problems.problem(
                "rate-limited",
                "Too many requests",
                429,
                "this API key is over its budget of "
                    + permit.limit()
                    + " requests per second; retry in "
                    + permit.retryAfterSeconds()
                    + " s",
                ApiKeyFilter.correlation(request)));
    response.setStatus(429);
    response.setHeader("Retry-After", Integer.toString(permit.retryAfterSeconds()));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(body);
  }
}
