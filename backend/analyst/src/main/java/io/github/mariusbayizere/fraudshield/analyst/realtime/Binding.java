package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a session is bound to at CONNECT (ADR 0115 point 2): the claims and the token's expiry. The
 * user, institution, role and sid never change for the socket's life; the token version and expiry
 * change only by re-authentication (point 3).
 *
 * @param claims the token's claims
 * @param expiresAt the token's {@code exp}
 */
record Binding(StaffClaims claims, Instant expiresAt) {

  Binding {
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  UUID userId() {
    return claims.userId();
  }

  UUID institutionId() {
    return claims.institutionId();
  }

  StaffRole role() {
    return claims.role();
  }

  UUID sessionId() {
    return claims.sessionId();
  }

  /** The (user, sid) pair the revocation backstop checks (point 3). */
  Pair pair() {
    return new Pair(claims.userId(), claims.sessionId());
  }

  /**
   * A distinct (user, sid) pair.
   *
   * @param userId the user
   * @param sessionId the sid
   */
  record Pair(UUID userId, UUID sessionId) {}
}
