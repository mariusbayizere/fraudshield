package io.github.mariusbayizere.fraudshield.ingest.web;

import io.github.mariusbayizere.fraudshield.ingest.application.Problems;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates machine clients on the ingestion paths only (FR-01-05): {@code X-API-Key} is
 * verified by the API-key module and the principal attached to the request; anything else is 401.
 * Keys are never looked at on other paths, so a key authenticates nothing on staff endpoints. Every
 * request gets a correlation id, echoed in {@code X-Correlation-Id} and in problem bodies.
 */
public final class ApiKeyFilter extends OncePerRequestFilter {

  /** Request attribute holding the {@link ApiPrincipal}. */
  public static final String PRINCIPAL = ApiKeyFilter.class.getName() + ".principal";

  /** Request attribute holding the correlation id. */
  public static final String CORRELATION = ApiKeyFilter.class.getName() + ".correlation";

  /** Paths guarded by API keys (OpenAPI operations with {@code x-required-scopes}). */
  public static final List<String> PROTECTED =
      List.of("/api/v1/transactions/", "/api/v1/decisions/", "/api/v1/jobs/");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ApiKeyAuthenticator authenticator;

  /**
   * Creates the filter.
   *
   * @param authenticator the API-key module's verifier
   */
  public ApiKeyFilter(ApiKeyAuthenticator authenticator) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
  }

  /**
   * The correlation id of a request.
   *
   * @param request the request
   * @return its id
   */
  public static UUID correlation(HttpServletRequest request) {
    Object id = request.getAttribute(CORRELATION);
    return id instanceof UUID uuid ? uuid : UUID.randomUUID();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID correlation = UUID.randomUUID();
    request.setAttribute(CORRELATION, correlation);
    response.setHeader("X-Correlation-Id", correlation.toString());
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (PROTECTED.stream().noneMatch(path::startsWith)) {
      chain.doFilter(request, response);
      return;
    }
    Optional<ApiPrincipal> principal = authenticator.authenticate(request.getHeader("X-API-Key"));
    if (principal.isEmpty()) {
      write(
          response,
          401,
          Problems.problem(
              "unauthorized",
              "Authentication required",
              401,
              "a valid X-API-Key is required",
              correlation));
      return;
    }
    request.setAttribute(PRINCIPAL, principal.get());
    chain.doFilter(request, response);
  }

  static void write(HttpServletResponse response, int status, Object body) throws IOException {
    response.setStatus(status);
    response.setContentType(Problems.MEDIA_TYPE);
    response
        .getOutputStream()
        .write(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
  }
}
