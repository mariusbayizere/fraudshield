package io.github.mariusbayizere.fraudshield.auth.session;

import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * Credentials handed to the browser at sign-in or refresh: the access token goes in the response
 * body and is kept in memory only; the refresh token and the CSRF token go in cookies (D-27).
 *
 * @param accessToken RS256 access token
 * @param expiresInSeconds access-token lifetime
 * @param refreshToken raw refresh token (only its hash is stored)
 * @param refreshExpiresAt end of the refresh token, for the cookie's Max-Age
 * @param csrfToken double-submit CSRF token
 * @param sessionId session (family) ID
 * @param account the signed-in account
 */
public record IssuedSession(
    String accessToken,
    long expiresInSeconds,
    String refreshToken,
    Instant refreshExpiresAt,
    String csrfToken,
    UUID sessionId,
    StaffAccount account) {

  @Override
  public String toString() {
    return "IssuedSession[sessionId=" + sessionId + ", tokens=<redacted>]";
  }
}
