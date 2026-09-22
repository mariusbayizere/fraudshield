package io.github.mariusbayizere.fraudshield.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of {@code api_keys} (ADR 0071). Authentication does not use it: the key lookup runs
 * before any institution is known, through the SECURITY DEFINER function {@code auth_find_api_key}
 * in explicit SQL.
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

  @Id private UUID id;

  @Column(name = "institution_id", nullable = false, updatable = false)
  private UUID institutionId;

  @Column(name = "key_id", nullable = false, updatable = false)
  private String keyId;

  @Column(nullable = false)
  private String name;

  @Column(name = "secret_hmac", nullable = false, updatable = false)
  private byte[] secretHmac;

  @Column(name = "pepper_version", nullable = false, updatable = false)
  private short pepperVersion;

  @Column(name = "last_four", nullable = false, updatable = false)
  private String lastFour;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private String[] scopes;

  @Column(nullable = false)
  private String state;

  @Column(name = "webhook_url")
  private String webhookUrl;

  @Column(name = "webhook_secret_ciphertext")
  private byte[] webhookSecretCiphertext;

  @Column(name = "webhook_secret_key_id")
  private String webhookSecretKeyId;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "replaced_by")
  private UUID replacedBy;

  /** For JPA. */
  protected ApiKeyEntity() {}

  /**
   * A new key row.
   *
   * @param id row ID
   * @param institutionId institution
   * @param keyId public key ID
   * @param name name
   * @param secretHmac HMAC of the secret
   * @param pepperVersion pepper version
   * @param lastFour last four characters
   * @param scopes scope values
   * @param webhookUrl webhook URL or null
   * @param webhookSecretCiphertext sealed webhook secret or null
   * @param webhookSecretKeyId seal key ID or null
   * @param createdBy administrator
   * @param createdAt creation time
   * @return the entity to persist
   */
  public static ApiKeyEntity create(
      UUID id,
      UUID institutionId,
      String keyId,
      String name,
      byte[] secretHmac,
      short pepperVersion,
      String lastFour,
      String[] scopes,
      String webhookUrl,
      byte[] webhookSecretCiphertext,
      String webhookSecretKeyId,
      UUID createdBy,
      Instant createdAt) {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.id = id;
    entity.institutionId = institutionId;
    entity.keyId = keyId;
    entity.name = name;
    entity.secretHmac = secretHmac.clone();
    entity.pepperVersion = pepperVersion;
    entity.lastFour = lastFour;
    entity.scopes = scopes.clone();
    entity.state = "ACTIVE";
    entity.webhookUrl = webhookUrl;
    entity.webhookSecretCiphertext =
        webhookSecretCiphertext == null ? null : webhookSecretCiphertext.clone();
    entity.webhookSecretKeyId = webhookSecretKeyId;
    entity.createdBy = createdBy;
    entity.createdAt = createdAt;
    return entity;
  }

  /**
   * Starts a rotation overlap.
   *
   * @param successor replacement key
   * @param until end of the overlap
   */
  public void startRotation(UUID successor, Instant until) {
    this.state = "ROTATING";
    this.replacedBy = successor;
    this.expiresAt = until;
  }

  /**
   * Revokes the key.
   *
   * @param at revocation time
   */
  public void revoke(Instant at) {
    this.state = "REVOKED";
    this.revokedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getInstitutionId() {
    return institutionId;
  }

  public String getKeyId() {
    return keyId;
  }

  public String getName() {
    return name;
  }

  public String getLastFour() {
    return lastFour;
  }

  public String[] getScopes() {
    return scopes.clone();
  }

  public String getState() {
    return state;
  }

  public String getWebhookUrl() {
    return webhookUrl;
  }

  public byte[] getWebhookSecretCiphertext() {
    return webhookSecretCiphertext == null ? null : webhookSecretCiphertext.clone();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }
}
