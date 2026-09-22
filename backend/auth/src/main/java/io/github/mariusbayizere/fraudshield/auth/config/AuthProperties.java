package io.github.mariusbayizere.fraudshield.auth.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Staff identity and API authentication configuration ({@code fraudshield.auth.*}). Every secret
 * comes from the environment or a mounted file; none has a default, so a deployment without them
 * fails at startup (H.1: fail fast on invalid configuration).
 *
 * @param environment API-key environment segment: dev, test, stg or prod
 * @param consoleBaseUrl base URL of the staff console, for links in emails
 * @param mailFrom sender address of staff email
 * @param tokenKeyHex HMAC key for unlock and reset tokens and reset codes, hex, at least 32 bytes
 * @param secretBoxKeyHex AES-256 key for secrets at rest, hex, 32 bytes
 * @param secretBoxKeyId identifier of the secret-box key
 * @param jwt access tokens
 * @param session refresh sessions, CSRF cookie and the session cache
 * @param lockout failed sign-in lock
 * @param rateLimits request limits
 * @param apiKeys API-key peppers
 * @param google Google sign-in
 * @param selfServiceDomains email domain to institution ID, for registration and Google sign-in
 *     (D-23)
 */
@ConfigurationProperties("fraudshield.auth")
public record AuthProperties(
    String environment,
    String consoleBaseUrl,
    String mailFrom,
    String tokenKeyHex,
    String secretBoxKeyHex,
    String secretBoxKeyId,
    Jwt jwt,
    Session session,
    Lockout lockout,
    RateLimits rateLimits,
    ApiKeys apiKeys,
    Google google,
    Map<String, UUID> selfServiceDomains) {

  /** Applies defaults to the non-secret settings. */
  public AuthProperties {
    session = Objects.requireNonNullElseGet(session, () -> new Session(null, null, null, null));
    lockout = Objects.requireNonNullElseGet(lockout, () -> new Lockout(null, null, null));
    rateLimits =
        Objects.requireNonNullElseGet(
            rateLimits, () -> new RateLimits(null, null, null, null, null, null, null, null));
    google =
        Objects.requireNonNullElseGet(
            google, () -> new Google(null, null, null, null, null, null, null, null));
    selfServiceDomains = selfServiceDomains == null ? Map.of() : Map.copyOf(selfServiceDomains);
  }

  /**
   * Access tokens (FR-07-04, D-27).
   *
   * @param issuer {@code iss} claim
   * @param audience {@code aud} claim
   * @param signingKeys RS256 keys, first one signs; the others only verify, during rotation
   * @param accessTokenTtl lifetime (default 15 minutes; the contract fixes {@code expires_in} 900)
   */
  public record Jwt(
      String issuer, String audience, List<SigningKey> signingKeys, Duration accessTokenTtl) {

    /** Applies defaults. */
    public Jwt {
      issuer = Objects.requireNonNullElse(issuer, "fraudshield");
      audience = Objects.requireNonNullElse(audience, "fraudshield-staff-api");
      signingKeys = signingKeys == null ? List.of() : List.copyOf(signingKeys);
      accessTokenTtl = Objects.requireNonNullElse(accessTokenTtl, Duration.ofMinutes(15));
    }
  }

  /**
   * An RS256 key pair.
   *
   * @param kid key ID published in the JWKS
   * @param privateKey PKCS#8 PEM private key file
   */
  public record SigningKey(String kid, Path privateKey) {}

  /**
   * Sessions (D-27).
   *
   * @param refreshTtl absolute lifetime of a sign-in's refresh-token family (default 7 days)
   * @param cacheTtl how long the session state (token version, family active) is cached in Redis
   *     (default 2 seconds)
   * @param localCacheTtl how long it is cached in process (default 1 second); with the Redis TTL
   *     this bounds invalidation below 5 seconds even when a pub/sub message is lost
   * @param secureCookies whether cookies carry {@code Secure} (default true; only local HTTP tests
   *     turn it off)
   */
  public record Session(
      Duration refreshTtl, Duration cacheTtl, Duration localCacheTtl, Boolean secureCookies) {

    /** Applies defaults. */
    public Session {
      refreshTtl = Objects.requireNonNullElse(refreshTtl, Duration.ofDays(7));
      cacheTtl = Objects.requireNonNullElse(cacheTtl, Duration.ofSeconds(2));
      localCacheTtl = Objects.requireNonNullElse(localCacheTtl, Duration.ofSeconds(1));
      secureCookies = Objects.requireNonNullElse(secureCookies, Boolean.TRUE);
    }
  }

  /**
   * Failed sign-in lock (FR-07-06, D-26).
   *
   * @param maxFailures consecutive failures that lock the account (default 5)
   * @param lockDuration automatic unlock after (default 30 minutes)
   * @param unlockTokenTtl lifetime of the emailed unlock link (default 1 hour)
   */
  public record Lockout(Integer maxFailures, Duration lockDuration, Duration unlockTokenTtl) {

    /** Applies defaults. */
    public Lockout {
      maxFailures = Objects.requireNonNullElse(maxFailures, 5);
      lockDuration = Objects.requireNonNullElse(lockDuration, Duration.ofMinutes(30));
      unlockTokenTtl = Objects.requireNonNullElse(unlockTokenTtl, Duration.ofHours(1));
    }
  }

  /**
   * Request limits (FR-07-06, D-26, E.8).
   *
   * @param loginPerIp sign-in attempts per IP per window (default 10)
   * @param loginPerOfficeIp for an address on the institution's office allowlist (default 100)
   * @param loginPerIpAndEmail per IP and email (default 10)
   * @param window window of the sign-in limits (default 15 minutes)
   * @param publicPerIp registration, availability, Google sign-in and reset requests per IP per
   *     window (default 20)
   * @param resetPerEmail reset-code requests per email per window (default 3)
   * @param officeIpCacheTtl how long an institution's office allowlist is cached (default 30 s)
   * @param minimumPublicResponse floor on the duration of enumeration-sensitive responses (default
   *     250 ms)
   */
  public record RateLimits(
      Integer loginPerIp,
      Integer loginPerOfficeIp,
      Integer loginPerIpAndEmail,
      Duration window,
      Integer publicPerIp,
      Integer resetPerEmail,
      Duration officeIpCacheTtl,
      Duration minimumPublicResponse) {

    /** Applies defaults. */
    public RateLimits {
      loginPerIp = Objects.requireNonNullElse(loginPerIp, 10);
      loginPerOfficeIp = Objects.requireNonNullElse(loginPerOfficeIp, 100);
      loginPerIpAndEmail = Objects.requireNonNullElse(loginPerIpAndEmail, 10);
      window = Objects.requireNonNullElse(window, Duration.ofMinutes(15));
      publicPerIp = Objects.requireNonNullElse(publicPerIp, 20);
      resetPerEmail = Objects.requireNonNullElse(resetPerEmail, 3);
      officeIpCacheTtl = Objects.requireNonNullElse(officeIpCacheTtl, Duration.ofSeconds(30));
      minimumPublicResponse =
          Objects.requireNonNullElse(minimumPublicResponse, Duration.ofMillis(250));
    }
  }

  /**
   * API keys (D-19).
   *
   * @param peppers HMAC peppers by version, hex, at least 32 bytes each
   * @param currentPepperVersion version used for new keys
   * @param cacheTtl how long a verified key record is cached (default 2 seconds, FR-06-07)
   * @param rotationOverlap how long a rotated key stays valid (default 24 hours)
   */
  public record ApiKeys(
      Map<Integer, String> peppers,
      Integer currentPepperVersion,
      Duration cacheTtl,
      Duration rotationOverlap) {

    /** Applies defaults. */
    public ApiKeys {
      peppers = peppers == null ? Map.of() : Map.copyOf(peppers);
      cacheTtl = Objects.requireNonNullElse(cacheTtl, Duration.ofSeconds(2));
      rotationOverlap = Objects.requireNonNullElse(rotationOverlap, Duration.ofHours(24));
    }
  }

  /**
   * Google sign-in (FR-07-03, D-23).
   *
   * @param clientId OAuth client ID, the ID token's required audience; sign-in is disabled when
   *     absent
   * @param clientSecret OAuth client secret
   * @param tokenUri token endpoint
   * @param jwksUri JWKS of the ID-token signer
   * @param revokeUri token revocation endpoint (FR-07-09)
   * @param issuers accepted {@code iss} values
   * @param allowedRedirectUris redirect URIs the console may use
   * @param timeout HTTP timeout per call (default 3 seconds, the FR-07-03 budget)
   */
  public record Google(
      String clientId,
      String clientSecret,
      String tokenUri,
      String jwksUri,
      String revokeUri,
      List<String> issuers,
      List<String> allowedRedirectUris,
      Duration timeout) {

    /** Applies defaults. */
    public Google {
      tokenUri = Objects.requireNonNullElse(tokenUri, "https://oauth2.googleapis.com/token");
      jwksUri = Objects.requireNonNullElse(jwksUri, "https://www.googleapis.com/oauth2/v3/certs");
      revokeUri = Objects.requireNonNullElse(revokeUri, "https://oauth2.googleapis.com/revoke");
      issuers =
          issuers == null
              ? List.of("https://accounts.google.com", "accounts.google.com")
              : List.copyOf(issuers);
      allowedRedirectUris =
          allowedRedirectUris == null ? List.of() : List.copyOf(allowedRedirectUris);
      timeout = Objects.requireNonNullElse(timeout, Duration.ofSeconds(3));
    }

    /**
     * Whether Google sign-in is configured.
     *
     * @return whether a client ID and secret are set
     */
    public boolean enabled() {
      return clientId != null && !clientId.isBlank() && clientSecret != null;
    }
  }
}
