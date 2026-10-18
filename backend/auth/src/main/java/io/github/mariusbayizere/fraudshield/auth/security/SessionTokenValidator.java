package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Refuses an access token whose session has ended (D-27): its token version is older than the
 * account's (password change, sign-out everywhere, role or status change, deactivation) or its
 * session was signed out.
 */
public final class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2TokenValidatorResult ENDED =
      OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "The session has ended", null));

  private final SessionStateCache sessions;

  /**
   * Creates the validator.
   *
   * @param sessions session-state cache
   */
  public SessionTokenValidator(SessionStateCache sessions) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    StaffClaims claims;
    try {
      claims = AccessTokens.claims(token);
    } catch (RuntimeException e) {
      return ENDED;
    }
    return sessions.isCurrent(claims) ? OAuth2TokenValidatorResult.success() : ENDED;
  }
}
