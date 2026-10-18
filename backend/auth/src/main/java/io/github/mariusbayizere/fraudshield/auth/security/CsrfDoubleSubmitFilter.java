package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Double-submit CSRF check (D-27) on the operations whose contract declares the {@code
 * X-CSRF-Token} parameter: the header must equal the {@code fs_csrf} cookie set at sign-in. A
 * cross-site page can make the browser send the cookie but cannot read it to copy it into the
 * header. The refresh cookie is SameSite=Strict as well; this is the second layer.
 */
public final class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

  /** Header carrying the token. */
  public static final String HEADER = "X-CSRF-Token";

  /** Cookie carrying the token. */
  public static final String COOKIE = "fs_csrf";

  private static final int MIN_LENGTH = 32;

  private final ContractPolicy policy;

  /**
   * Creates the filter.
   *
   * @param policy the contract policy
   */
  public CsrfDoubleSubmitFilter(ContractPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean required =
        policy
            .match(request.getMethod(), path)
            .map(ContractPolicy.Operation::requiresCsrf)
            .orElse(false);
    if (required && !matches(request)) {
      ProblemWriter.write(
          ProblemException.of("forbidden", 403, "Forbidden", "Missing or mismatched CSRF token"),
          request,
          response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean matches(HttpServletRequest request) {
    String header = request.getHeader(HEADER);
    Optional<String> cookie = cookie(request);
    return header != null
        && header.length() >= MIN_LENGTH
        && cookie.isPresent()
        && Crypto.constantTimeEquals(
            header.getBytes(StandardCharsets.UTF_8), cookie.get().getBytes(StandardCharsets.UTF_8));
  }

  private static Optional<String> cookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (COOKIE.equals(cookie.getName())) {
        return Optional.of(cookie.getValue());
      }
    }
    return Optional.empty();
  }
}
