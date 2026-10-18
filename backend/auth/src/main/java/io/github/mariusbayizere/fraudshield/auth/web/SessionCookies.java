package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.auth.security.CsrfDoubleSubmitFilter;
import io.github.mariusbayizere.fraudshield.auth.session.IssuedSession;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseCookie;

/**
 * The session cookies (D-27, FR-07-04): the refresh token is httpOnly, Secure, SameSite=Strict and
 * scoped to the refresh path, so no script and no other endpoint ever sees it; the CSRF token is
 * readable by the console's script, which copies it into {@code X-CSRF-Token}.
 */
public final class SessionCookies {

  /** Refresh-token cookie name (contract securitySchemes.refreshCookie). */
  public static final String REFRESH = "fs_refresh";

  /** Path the refresh cookie is scoped to. */
  public static final String REFRESH_PATH = "/api/v1/auth/refresh";

  private final boolean secure;
  private final Clock clock;

  /**
   * Creates the cookie factory.
   *
   * @param secure whether cookies carry Secure (always, except in local HTTP tests)
   * @param clock clock
   */
  public SessionCookies(boolean secure, Clock clock) {
    this.secure = secure;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Set-Cookie values for a session.
   *
   * @param session the session
   * @return refresh and CSRF cookies
   */
  public List<String> issue(IssuedSession session) {
    Duration maxAge = Duration.between(clock.instant(), session.refreshExpiresAt());
    return List.of(
        ResponseCookie.from(REFRESH, session.refreshToken())
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path(REFRESH_PATH)
            .maxAge(maxAge)
            .build()
            .toString(),
        ResponseCookie.from(CsrfDoubleSubmitFilter.COOKIE, session.csrfToken())
            .httpOnly(false)
            .secure(secure)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()
            .toString());
  }

  /**
   * Set-Cookie values that delete both cookies (sign-out).
   *
   * @return expired cookies
   */
  public List<String> clear() {
    return List.of(
        ResponseCookie.from(REFRESH, "")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path(REFRESH_PATH)
            .maxAge(0)
            .build()
            .toString(),
        ResponseCookie.from(CsrfDoubleSubmitFilter.COOKIE, "")
            .secure(secure)
            .sameSite("Strict")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
  }
}
