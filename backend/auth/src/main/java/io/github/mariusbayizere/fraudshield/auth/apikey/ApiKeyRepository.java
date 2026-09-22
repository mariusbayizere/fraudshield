package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads and writes {@code api_keys} (D-19, D-31). */
public final class ApiKeyRepository {

  private static final String COLUMNS =
      "id, institution_id, key_id, name, scopes, last_four, state, webhook_url, created_at,"
          + " expires_at, last_used_at";

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template of the application role
   */
  public ApiKeyRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
    return jdbc.queryForObject(
        """
        INSERT INTO api_keys (institution_id, key_id, name, secret_hmac, pepper_version, last_four,
          scopes, webhook_url, webhook_secret_ciphertext, webhook_secret_key_id, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
        RETURNING
        """
            + COLUMNS,
        ApiKeyRepository::map,
        key.institutionId(),
        key.keyId(),
        key.name(),
        key.secretHmac(),
        key.pepperVersion(),
        key.lastFour(),
        "{" + String.join(",", key.scopes().stream().map(ApiKeyScope::value).toList()) + "}",
        key.webhookUrl(),
        key.webhookSecretCiphertext(),
        key.webhookSecretKeyId(),
        key.createdBy());
  }

  /**
   * A key of the current institution, locked for update.
   *
   * @param keyId public key ID
   * @return the key
   */
  public Optional<ApiKeyRecord> findForUpdate(String keyId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM api_keys WHERE key_id = ? FOR UPDATE",
            ApiKeyRepository::map,
            keyId)
        .stream()
        .findFirst();
  }

  /**
   * Keys of the current institution, newest first.
   *
   * @return the keys
   */
  public List<ApiKeyRecord> list() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM api_keys ORDER BY created_at DESC, id DESC",
        ApiKeyRepository::map);
  }

  /**
   * Whether a live key of the current institution already has this name.
   *
   * @param name name
   * @return whether it is taken
   */
  public boolean liveNameTaken(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM api_keys WHERE name = ? AND state <> 'REVOKED')",
            Boolean.class,
            name));
  }

  /**
   * Starts a rotation overlap on the old key.
   *
   * @param id old key
   * @param replacedBy new key
   * @param expiresAt end of the overlap
   */
  public void markRotating(UUID id, UUID replacedBy, Instant expiresAt) {
    jdbc.update(
        "UPDATE api_keys SET state = 'ROTATING', replaced_by = ?, expires_at = ? WHERE id = ?",
        replacedBy,
        Timestamp.from(expiresAt),
        id);
  }

  /**
   * Revokes a key.
   *
   * @param id key
   * @param at revocation time
   */
  public void revoke(UUID id, Instant at) {
    jdbc.update(
        "UPDATE api_keys SET state = 'REVOKED', revoked_at = ? WHERE id = ? AND state <> 'REVOKED'",
        Timestamp.from(at),
        id);
  }

  /**
   * The ciphertext webhook secret of a key.
   *
   * @param id key
   * @return the ciphertext, or empty without a webhook
   */
  public Optional<byte[]> webhookSecretCiphertext(UUID id) {
    return jdbc
        .query(
            "SELECT webhook_secret_ciphertext FROM api_keys WHERE id = ?",
            (row, i) -> row.getBytes(1),
            id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Records use of a key.
   *
   * @param id key
   * @param at time of use
   */
  public void touch(UUID id, Instant at) {
    jdbc.update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", Timestamp.from(at), id);
  }

  private static ApiKeyRecord map(ResultSet row, int index) throws SQLException {
    return new ApiKeyRecord(
        row.getObject("id", UUID.class),
        row.getObject("institution_id", UUID.class),
        row.getString("key_id"),
        row.getString("name"),
        scopes(row.getArray("scopes")),
        row.getString("last_four"),
        row.getString("state"),
        row.getString("webhook_url"),
        instant(row, "created_at"),
        instant(row, "expires_at"),
        instant(row, "last_used_at"));
  }

  private static List<ApiKeyScope> scopes(Array array) throws SQLException {
    List<ApiKeyScope> scopes = new ArrayList<>();
    for (Object value : (Object[]) array.getArray()) {
      ApiKeyScope.parse((String) value).ifPresent(scopes::add);
    }
    return scopes;
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
