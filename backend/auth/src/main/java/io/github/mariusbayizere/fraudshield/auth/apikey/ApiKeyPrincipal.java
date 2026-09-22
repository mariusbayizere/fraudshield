package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.util.Set;
import java.util.UUID;

/**
 * An authenticated machine client (FR-07-05). It holds scopes, never a staff role, so it can reach
 * only the operations whose contract entry lists one of its scopes.
 *
 * @param apiKeyId row ID
 * @param keyId public key ID
 * @param institutionId institution
 * @param scopes granted scopes
 */
public record ApiKeyPrincipal(
    UUID apiKeyId, String keyId, UUID institutionId, Set<ApiKeyScope> scopes) {

  /** Copies the scopes. */
  public ApiKeyPrincipal {
    scopes = Set.copyOf(scopes);
  }
}
