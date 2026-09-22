package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

  private final ApiKeyAuthenticator authenticator;

  /**
   * Creates the filter.
   *
   * @param authenticator the authenticator
   */
  public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
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
      ProblemWriter.write(ProblemException.unauthorized(), request, response);
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
