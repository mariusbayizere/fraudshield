package io.github.mariusbayizere.fraudshield.auth.apikey;

import io.github.mariusbayizere.fraudshield.auth.persistence.ApiKeyEntity;
import io.github.mariusbayizere.fraudshield.auth.persistence.ApiKeyJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes {@code api_keys} (D-19, D-31) with hybrid persistence (ADR 0071): the
 * authentication lookup runs before any institution is known, through a SECURITY DEFINER function
 * in explicit SQL; the administrator lifecycle is tenant-scoped JPA. Writes flush at once.
 */
public final class ApiKeyRepository {

  private final JdbcTemplate jdbc;
  private final ApiKeyJpaRepository keys;
  private final EntityManager entities;
  private final Clock clock;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template of the application role (the pre-tenant credential lookup)
   * @param keys Spring Data repository of API keys (the administrator lifecycle)
   * @param entities shared, transaction-bound entity manager
   * @param clock clock
   */
  public ApiKeyRepository(
      JdbcTemplate jdbc, ApiKeyJpaRepository keys, EntityManager entities, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.entities = Objects.requireNonNull(entities, "entities");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * What authentication needs, looked up before any tenant is known (SECURITY DEFINER, V2).
   *
   * @param id row ID
   * @param institutionId institution
   * @param secretHmac stored HMAC
   * @param pepperVersion pepper used for the HMAC
   * @param scopes scopes
   * @param state state
   * @param expiresAt end of a rotation overlap, or null
   */
  public record Credential(
      UUID id,
      UUID institutionId,
      byte[] secretHmac,
      int pepperVersion,
      List<ApiKeyScope> scopes,
      String state,
      Instant expiresAt) {

    /** Copies the arrays. */
    public Credential {
      secretHmac = secretHmac.clone();
      scopes = List.copyOf(scopes);
    }

    @Override
    public byte[] secretHmac() {
      return secretHmac.clone();
    }
  }

  /**
   * Looks up a key for authentication.
   *
   * @param keyId public key ID
   * @return the credential
   */
  public Optional<Credential> findCredential(String keyId) {
    return jdbc
        .query(
            """
            SELECT api_key_id, institution_id, secret_hmac, pepper_version, scopes, state, expires_at
            FROM auth_find_api_key(?)
            """,
            (row, i) ->
                new Credential(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getBytes(3),
                    row.getInt(4),
                    scopes(row.getArray(5)),
                    row.getString(6),
                    instant(row, 7)),
            keyId)
        .stream()
        .findFirst();
  }

  /**
   * A new key.
   *
   * @param institutionId institution
   * @param keyId public key ID
   * @param name name
   * @param secretHmac HMAC of the secret
   * @param pepperVersion pepper version
   * @param lastFour last four characters of the secret
   * @param scopes scopes
   * @param webhookUrl webhook URL, or null
   * @param webhookSecretCiphertext ciphertext webhook secret, or null
   * @param webhookSecretKeyId key ID of the seal, or null
   * @param createdBy administrator
   */
  public record NewKey(
      UUID institutionId,
      String keyId,
      String name,
      byte[] secretHmac,
      int pepperVersion,
      String lastFour,
      List<ApiKeyScope> scopes,
      String webhookUrl,
      byte[] webhookSecretCiphertext,
      String webhookSecretKeyId,
      UUID createdBy) {

    /** Copies the arrays. */
    public NewKey {
      secretHmac = secretHmac.clone();
      webhookSecretCiphertext =
          webhookSecretCiphertext == null ? null : webhookSecretCiphertext.clone();
      scopes = List.copyOf(scopes);
    }

    @Override
    public byte[] secretHmac() {
      return secretHmac.clone();
    }

    @Override
    public byte[] webhookSecretCiphertext() {
      return webhookSecretCiphertext == null ? null : webhookSecretCiphertext.clone();
    }
  }

  /**
   * Inserts a key.
   *
   * @param key the key
   * @return the stored record
   */
  public ApiKeyRecord insert(NewKey key) {
    ApiKeyEntity entity =
        ApiKeyEntity.create(
            UUID.randomUUID(),
            key.institutionId(),
            key.keyId(),
            key.name(),
            key.secretHmac(),
            (short) key.pepperVersion(),
            key.lastFour(),
            key.scopes().stream().map(ApiKeyScope::value).toArray(String[]::new),
            key.webhookUrl(),
            key.webhookSecretCiphertext(),
            key.webhookSecretKeyId(),
            key.createdBy(),
            clock.instant());
    entities.persist(entity);
    entities.flush();
    return record(entity);
  }

  /**
   * A key of the current institution, locked for update.
   *
   * @param keyId public key ID
   * @return the key
   */
  public Optional<ApiKeyRecord> findForUpdate(String keyId) {
    return keys.findIdByKeyId(keyId)
        .map(
            id -> {
              // Lock by refresh, as StaffAccountRepository does: a held copy is replaced, not
              // reused
              ApiKeyEntity key = entities.find(ApiKeyEntity.class, id);
              entities.refresh(key, LockModeType.PESSIMISTIC_WRITE);
              return record(key);
            });
  }

  /**
   * Keys of the current institution, newest first: one query.
   *
   * @return the keys
   */
  public List<ApiKeyRecord> list() {
    return keys.findAllByOrderByCreatedAtDescIdDesc().stream()
        .map(ApiKeyRepository::record)
        .toList();
  }

  /**
   * Whether a live key of the current institution already has this name.
   *
   * @param name name
   * @return whether it is taken
   */
  public boolean liveNameTaken(String name) {
    return keys.existsByNameAndStateNot(name, "REVOKED");
  }

  /**
   * Starts a rotation overlap on the old key.
   *
   * @param id old key
   * @param replacedBy new key
   * @param expiresAt end of the overlap
   */
  public void markRotating(UUID id, UUID replacedBy, Instant expiresAt) {
    keys.findById(id).orElseThrow().startRotation(replacedBy, expiresAt);
    entities.flush();
  }

  /**
   * Revokes a key; a revoked key stays as it is.
   *
   * @param id key
   * @param at revocation time
   */
  public void revoke(UUID id, Instant at) {
    ApiKeyEntity key = keys.findById(id).orElseThrow();
    if (!"REVOKED".equals(key.getState())) {
      key.revoke(at);
      entities.flush();
    }
  }

  /**
   * The sealed webhook secret of a key.
   *
   * @param id key
   * @return the ciphertext, or empty without a webhook
   */
  public Optional<byte[]> webhookSecretCiphertext(UUID id) {
    return keys.findById(id).map(ApiKeyEntity::getWebhookSecretCiphertext);
  }

  /**
   * Records use of a key.
   *
   * @param id key
   * @param at time of use
   */
  public void touch(UUID id, Instant at) {
    keys.touch(id, at);
  }

  private static ApiKeyRecord record(ApiKeyEntity key) {
    return new ApiKeyRecord(
        key.getId(),
        key.getInstitutionId(),
        key.getKeyId(),
        key.getName(),
        java.util.Arrays.stream(key.getScopes())
            .map(ApiKeyScope::parse)
            .flatMap(Optional::stream)
            .toList(),
        key.getLastFour(),
        key.getState(),
        key.getWebhookUrl(),
        key.getCreatedAt(),
        key.getExpiresAt(),
        key.getLastUsedAt());
  }

  private static List<ApiKeyScope> scopes(Array array) throws SQLException {
    List<ApiKeyScope> scopes = new ArrayList<>();
    for (Object value : (Object[]) array.getArray()) {
      ApiKeyScope.parse((String) value).ifPresent(scopes::add);
    }
    return scopes;
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
