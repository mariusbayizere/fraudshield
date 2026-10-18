package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.auth.config.AuthProperties;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Request limits for the public authentication endpoints (FR-07-06, D-26).
 *
 * <p>Sign-in: 10 attempts per 15 minutes per IP, raised to the office ceiling for an address on the
 * account's institution allowlist, and 10 per IP and email, so one user behind a shared office
 * address cannot exhaust it for colleagues. Keys are hashed, so Redis holds neither addresses nor
 * emails.
 */
public final class LoginThrottle {

  private final RateLimiter limiter;
  private final OfficeIpAllowlist offices;
  private final AuthProperties.RateLimits limits;

  /**
   * Creates the throttle.
   *
   * @param limiter rate limiter
   * @param offices office allowlist
   * @param limits configured limits
   */
  public LoginThrottle(
      RateLimiter limiter, OfficeIpAllowlist offices, AuthProperties.RateLimits limits) {
    this.limiter = Objects.requireNonNull(limiter, "limiter");
    this.offices = Objects.requireNonNull(offices, "offices");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /**
   * Counts a sign-in attempt.
   *
   * @param ip client address
   * @param email submitted email
   * @param institutionId institution of the email, or null if no account has it
   * @throws ProblemException 429 with Retry-After when a limit is reached
   */
  public void login(String ip, String email, UUID institutionId) {
    boolean office = institutionId != null && ip != null && offices.contains(institutionId, ip);
    check("login:ip:" + digest(ip), office ? limits.loginPerOfficeIp() : limits.loginPerIp());
    check(
        "login:ipemail:" + digest(ip + "|" + email.toLowerCase(Locale.ROOT)),
        limits.loginPerIpAndEmail());
  }

  /**
   * Counts a request to a public, enumeration-sensitive endpoint (registration, availability,
   * Google sign-in, reset).
   *
   * @param endpoint endpoint name
   * @param ip client address
   */
  public void publicEndpoint(String endpoint, String ip) {
    check(endpoint + ":ip:" + digest(ip), limits.publicPerIp());
  }

  /**
   * Counts a reset-code request for an email.
   *
   * @param email submitted email
   */
  public void resetForEmail(String email) {
    check("reset:email:" + digest(email.toLowerCase(Locale.ROOT)), limits.resetPerEmail());
  }

  /**
   * Counts a request against a custom limit.
   *
   * @param name limit name
   * @param subject what is limited (hashed before use)
   * @param limit requests per window
   * @param window window
   */
  public void limit(String name, String subject, int limit, java.time.Duration window) {
    RateLimiter.Decision decision = limiter.attempt(name + ":" + digest(subject), limit, window);
    if (!decision.allowed()) {
      throw ProblemException.rateLimited(decision.retryAfterSeconds());
    }
  }

  private void check(String key, int limit) {
    RateLimiter.Decision decision = limiter.attempt(key, limit, limits.window());
    if (!decision.allowed()) {
      throw ProblemException.rateLimited(decision.retryAfterSeconds());
    }
  }

  private static String digest(String value) {
    return Crypto.base64url(Crypto.sha256(String.valueOf(value))).substring(0, 22);
  }
}
