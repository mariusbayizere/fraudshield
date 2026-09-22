package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An API key as listed to administrators: never the secret or its HMAC (FR-06-07).
 *
 * @param id row ID
 * @param institutionId institution
 * @param keyId public key ID
 * @param name name
 * @param scopes scopes
 * @param lastFour last four characters of the secret
 * @param state ACTIVE, ROTATING or REVOKED
 * @param webhookUrl webhook URL, or null
 * @param createdAt creation time
 * @param expiresAt end of the rotation overlap, or null
 * @param lastUsedAt last use (updated at most once a minute), or null
 */
public record ApiKeyRecord(
    UUID id,
    UUID institutionId,
    String keyId,
    String name,
    List<ApiKeyScope> scopes,
    String lastFour,
    String state,
    String webhookUrl,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt) {

  /** Copies the scopes. */
  public ApiKeyRecord {
    scopes = List.copyOf(scopes);
  }
}
