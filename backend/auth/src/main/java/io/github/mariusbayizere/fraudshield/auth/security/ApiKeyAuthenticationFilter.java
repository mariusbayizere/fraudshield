package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyPrincipal;
import io.github.mariusbayizere.fraudshield.auth.apikey.KeyStoreUnavailableException;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-API-Key} (D-19). A request carrying a key that does not verify is answered
 * 401 at once, never passed on anonymously; a request carrying both a key and a bearer token is
 * ambiguous and also refused. The answers are those of docs/architecture/api-key-cache-contract.md
 * §3: 503 only when the key store is unavailable, 500 for any other error, 429 before any read for
 * an address already over the failure limit.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  /** The API-key header. */
  public static final String HEADER = "X-API-Key";

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
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
    // R0(a): an address already over the failure limit is refused before any read, so a flood of
    // bad keys cannot keep the key store busy past the limit.
    String failureKey = FAILURE_KEY + request.getRemoteAddr();
    RateLimiter.Decision allowed = limiter.check(failureKey, MAX_FAILURES, FAILURE_WINDOW);
    if (!allowed.allowed()) {
      ProblemWriter.write(
          ProblemException.rateLimited(allowed.retryAfterSeconds()), request, response);
      return;
    }
    Optional<ApiKeyPrincipal> principal;
    try {
      principal = authenticator.authenticate(key);
    } catch (KeyStoreUnavailableException storeDown) {
      // The key is not known to be bad, so the client is told to retry rather than 401, and the
      // failure is not counted (threat model R-13).
      ProblemWriter.write(ProblemException.serviceUnavailable(1), request, response);
      return;
    } catch (RuntimeException error) {
      // R7: not an outage, so not a 503; not the client's fault, so neither counted nor a 401.
      LOG.error("API-key verification failed", error);
      ProblemWriter.write(ProblemException.internal(), request, response);
      return;
    }
    if (principal.isEmpty()) {
      RateLimiter.Decision decision = limiter.attempt(failureKey, MAX_FAILURES, FAILURE_WINDOW);
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
