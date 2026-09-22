package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-API-Key} (D-19). A request carrying a key that does not verify is answered
 * 401 at once, never passed on anonymously; a request carrying both a key and a bearer token is
 * ambiguous and also refused.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  /** The API-key header. */
  public static final String HEADER = "X-API-Key";

  private static final String FAILURE_KEY = "api-key-failures:ip:";
  private static final int MAX_FAILURES = 100;
  private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

  private final ApiKeyAuthenticator authenticator;
  private final RateLimiter limiter;

  /**
   * Creates the filter.
   *
   * @param authenticator the authenticator
   * @param limiter limits failed keys per client address (review finding 2)
   */
  public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, RateLimiter limiter) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key == null) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getHeader("Authorization") != null) {
      ProblemWriter.write(ProblemException.unauthorized(), request, response);
      return;
    }
    var principal = authenticator.authenticate(key);
    if (principal.isEmpty()) {
      RateLimiter.Decision decision =
          limiter.attempt(FAILURE_KEY + request.getRemoteAddr(), MAX_FAILURES, FAILURE_WINDOW);
      ProblemWriter.write(
          decision.allowed()
              ? ProblemException.unauthorized()
              : ProblemException.rateLimited(decision.retryAfterSeconds()),
          request,
          response);
      return;
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new ApiKeyAuthentication(principal.get()));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
