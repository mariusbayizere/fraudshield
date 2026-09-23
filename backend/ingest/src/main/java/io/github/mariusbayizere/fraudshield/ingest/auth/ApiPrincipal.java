package io.github.mariusbayizere.fraudshield.ingest.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated machine client. The institution comes from the key, never from the request body,
 * so a client cannot act for another institution (ADR 0012, tenant isolation).
 *
 * @param apiKeyId the key's row id ({@code api_keys.id})
 * @param institutionId the key's institution
 * @param scopes the key's scopes
 */
public record ApiPrincipal(UUID apiKeyId, UUID institutionId, Set<ApiScope> scopes) {

  /** Requires every component and copies the scopes. */
  public ApiPrincipal {
    Objects.requireNonNull(apiKeyId, "apiKeyId");
    Objects.requireNonNull(institutionId, "institutionId");
    scopes = Set.copyOf(scopes);
  }

  /**
   * Whether the key carries a scope.
   *
   * @param scope the scope
   * @return true when granted
   */
  public boolean has(ApiScope scope) {
    return scopes.contains(scope);
  }
}
