package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyPrincipal;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyService;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiScope;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookEndpoints;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Binds the ingestion API's two ports to the staff-identity module (M7), which owns API keys:
 * {@link ApiKeyAuthenticator} to M7's verifier of {@code X-API-Key}, and {@link WebhookEndpoints}
 * to the webhook URL and sealed signing secrets M7 stores with each key. The staff module's
 * security chain authorises every request against the contract first (ADR 0014); the ingestion
 * filter then attaches the principal its controllers read.
 */
@Configuration(proxyBeanMethods = false)
public class StaffIdentityWiring {

  /** The paths {@code ApiDocsController} serves. */
  private static final Set<String> API_DOCS = Set.of("/api/docs", "/api/docs/openapi.yaml");

  /**
   * The ingestion port over M7's authenticator. Both check the same key against the same cached
   * record, so a key revoked in M7 is refused here within the same 2 seconds (FR-06-07).
   *
   * @param keys M7's authenticator
   * @return the port
   */
  @Bean
  ApiKeyAuthenticator ingestApiKeys(
      io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator keys) {
    return rawKey -> keys.authenticate(rawKey).map(StaffIdentityWiring::principal);
  }

  /**
   * The webhook registry over M7's key records.
   *
   * @param keys M7's API-key service
   * @return the registry
   */
  @Bean
  WebhookEndpoints webhookEndpoints(ApiKeyService keys) {
    return institution ->
        keys.webhookEndpoint(institution)
            .map(e -> new WebhookEndpoints.Endpoint(URI.create(e.url()), e.secrets()));
  }

  /**
   * {@code /api/docs} serves the frozen contract itself (FR-01-07), so it is not an operation in
   * that contract, and the staff module's chain, which denies whatever the contract does not list,
   * would refuse it. This chain answers it first, to anyone, and nothing else.
   *
   * @param http security builder
   * @return the chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Order(0)
  SecurityFilterChain apiDocsSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            request ->
                HttpMethod.GET.matches(request.getMethod())
                    && API_DOCS.contains(
                        request.getRequestURI().substring(request.getContextPath().length())))
        // CSRF protection stays on: the chain matches GET only, which it never checks.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  static ApiPrincipal principal(ApiKeyPrincipal key) {
    EnumSet<ApiScope> scopes = EnumSet.noneOf(ApiScope.class);
    for (ApiKeyScope scope : key.scopes()) {
      scopes.add(
          ApiScope.parse(scope.value())
              .orElseThrow(() -> new IllegalStateException("unmapped API-key scope " + scope)));
    }
    return new ApiPrincipal(key.apiKeyId(), key.institutionId(), scopes);
  }
}
