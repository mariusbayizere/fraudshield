package io.github.mariusbayizere.fraudshield.auth.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes {@code refresh_tokens} (D-27). Only a SHA-256 of each token is stored. A token
 * family is one sign-in; its ID is the access token's {@code sid}.
 */
public final class RefreshTokenRepository {

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template of the application role
   */
  public RefreshTokenRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * A stored refresh token.
   *
   * @param id row ID
   * @param userId account
   * @param familyId family (session) ID
   * @param expiresAt expiry
   * @param revokedAt revocation time, or null
   * @param replacedBy successor after rotation, or null
   * @param oauthProvider GOOGLE when the session began with Google sign-in, else null
   */
  public record StoredToken(
      UUID id,
      UUID userId,
      UUID familyId,
      Instant expiresAt,
      Instant revokedAt,
      UUID replacedBy,
      String oauthProvider) {

    /**
     * Whether this token was already rotated, so presenting it again means it was copied.
     *
     * @return whether it was replaced
     */
    public boolean rotated() {
      return replacedBy != null;
    }
  }

  /**
   * The institution of a token hash, before any tenant is known (SECURITY DEFINER lookup, V2).
   *
   * @param tokenHash SHA-256 of the token
   * @return the token row ID and institution
   */
  public Optional<TokenRef> findRef(byte[] tokenHash) {
    return jdbc
        .query(
            "SELECT refresh_token_id, institution_id FROM auth_find_refresh_token(?)",
            (row, i) -> new TokenRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            (Object) tokenHash)
        .stream()
        .findFirst();
  }

  /**
   * A token row's ID and institution.
   *
   * @param id row ID
   * @param institutionId institution
   */
  public record TokenRef(UUID id, UUID institutionId) {}

  /**
   * The account a token belongs to, without locking (the caller locks the account first).
   *
   * @param id row ID
   * @return the account ID
   */
  public Optional<UUID> ownerOf(UUID id) {
    return jdbc
        .query(
            "SELECT user_id FROM refresh_tokens WHERE id = ?",
            (row, i) -> row.getObject(1, UUID.class),
            id)
        .stream()
        .findFirst();
  }

  /**
   * A token, locked for update so two concurrent refreshes cannot both rotate it.
   *
   * @param id row ID
   * @return the token
   */
  public Optional<StoredToken> findForUpdate(UUID id) {
    return jdbc
        .query(
            """
            SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by, oauth_provider
            FROM refresh_tokens WHERE id = ? FOR UPDATE
            """,
            (row, i) ->
                new StoredToken(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getObject(3, UUID.class),
                    row.getTimestamp(4).toInstant(),
                    row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant(),
                    row.getObject(6, UUID.class),
                    row.getString(7)),
            id)
        .stream()
        .findFirst();
  }

  /**
   * Stores a new token.
   *
   * @param token what to store
   * @return the row ID
   */
  public UUID insert(NewToken token) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO refresh_tokens (id, institution_id, user_id, family_id, token_hash, expires_at,
          created_by_ip, oauth_provider, user_agent)
        VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?)
        """,
        id,
        token.institutionId(),
        token.userId(),
        token.familyId(),
        token.tokenHash(),
        Timestamp.from(token.expiresAt()),
        token.ipAddress(),
        token.oauthProvider(),
        token.userAgent());
    return id;
  }

  /**
   * A token to store.
   *
   * @param institutionId institution
   * @param userId account
   * @param familyId family (session) ID
   * @param tokenHash SHA-256 of the token
   * @param expiresAt expiry
   * @param ipAddress client address, or null
   * @param oauthProvider GOOGLE or null
   * @param userAgent client user agent, or null
   */
  public record NewToken(
      UUID institutionId,
      UUID userId,
      UUID familyId,
      byte[] tokenHash,
      Instant expiresAt,
      String ipAddress,
      String oauthProvider,
      String userAgent) {

    /** Copies the hash. */
    public NewToken {
      tokenHash = tokenHash.clone();
    }

    @Override
    public byte[] tokenHash() {
      return tokenHash.clone();
    }
  }

  /**
   * Marks a token rotated.
   *
   * @param id rotated token
   * @param replacedBy successor
   * @param at rotation time
   */
  public void markRotated(UUID id, UUID replacedBy, Instant at) {
    jdbc.update(
        "UPDATE refresh_tokens SET replaced_by = ?, revoked_at = ? WHERE id = ?",
        replacedBy,
        Timestamp.from(at),
        id);
  }

  /**
   * Revokes every live token of a family (sign-out, reuse detection).
   *
   * @param familyId family ID
   * @param at revocation time
   * @return tokens revoked
   */
  public int revokeFamily(UUID familyId, Instant at) {
    return jdbc.update(
        "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
        Timestamp.from(at),
        familyId);
  }

  /**
   * Revokes every live token of an account (password change, sign-out everywhere, deactivation).
   *
   * @param userId account
   * @param at revocation time
   * @return tokens revoked
   */
  public int revokeAllForUser(UUID userId, Instant at) {
    return jdbc.update(
        "UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
        Timestamp.from(at),
        userId);
  }

  /**
   * Whether a family still has a live token, that is, whether the session is signed in.
   *
   * @param familyId family ID
   * @return whether the session is active
   */
  public boolean familyActive(UUID familyId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM refresh_tokens
                           WHERE family_id = ? AND revoked_at IS NULL AND expires_at > now())
            """,
            Boolean.class,
            familyId));
  }
}
