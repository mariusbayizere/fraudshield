package io.github.mariusbayizere.fraudshield.auth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Issues and verifies RS256 staff access tokens (FR-07-04). */
public final class AccessTokens {

  /** Claim holding the role. */
  public static final String ROLE = "role";

  /** Claim holding the institution. */
  public static final String INSTITUTION_ID = "institution_id";

  /** Claim holding the account token version (D-27). */
  public static final String TOKEN_VERSION = "token_version";

  /** Claim holding the session ID (ADR 0014). */
  public static final String SESSION_ID = "sid";

  private static final String FIRST_NAME = "first_name";
  private static final String EMAIL = "email";

  private final SigningKeys keys;
  private final String issuer;
  private final String audience;
  private final Duration ttl;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param keys signing keys
   * @param issuer {@code iss}
   * @param audience {@code aud}
   * @param ttl token lifetime
   * @param clock clock
   */
  public AccessTokens(SigningKeys keys, String issuer, String audience, Duration ttl, Clock clock) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.issuer = Objects.requireNonNull(issuer, "issuer");
    this.audience = Objects.requireNonNull(audience, "audience");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Token lifetime in seconds, the {@code expires_in} of the token response.
   *
   * @return seconds
   */
  public long ttlSeconds() {
    return ttl.toSeconds();
  }

  /**
   * Issues a signed token.
   *
   * @param claims staff claims
   * @return the compact JWT
   */
  public String issue(StaffClaims claims) {
    Instant now = clock.instant();
    JWTClaimsSet set =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(claims.userId().toString())
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .claim(ROLE, claims.role().name())
            .claim(FIRST_NAME, claims.firstName())
            .claim(EMAIL, claims.email())
            .claim(INSTITUTION_ID, claims.institutionId().toString())
            .claim(TOKEN_VERSION, claims.tokenVersion())
            .claim(SESSION_ID, claims.sessionId().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.active().getKeyID()).build(), set);
    try {
      JWSSigner signer = new RSASSASigner(keys.active());
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign the access token", e);
    }
    return jwt.serialize();
  }

  /**
   * A decoder that accepts only RS256 tokens signed by one of the keys, with this issuer and
   * audience, inside their lifetime, followed by the extra validators (the session check).
   *
   * @param extra further validators
   * @return the decoder
   */
  public JwtDecoder decoder(List<OAuth2TokenValidator<Jwt>> extra) {
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys.all())));
    // Spring validates the claims below; Nimbus would otherwise check exp against the system clock.
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    OAuth2TokenValidator<Jwt> strictExpiry = this::notOnOrAfterExpiry;
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);
    List<OAuth2TokenValidator<Jwt>> validators =
        new java.util.ArrayList<>(
            List.of(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(
                    JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>(ROLE, role -> role != null && isRole(role)),
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
                strictExpiry));
    validators.addAll(extra);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }

  /**
   * RFC 7519 section 4.1.4: a token must not be accepted on or after {@code exp}. Spring's
   * timestamp validator still accepts it at the exact expiry instant.
   */
  private OAuth2TokenValidatorResult notOnOrAfterExpiry(Jwt jwt) {
    Instant expiry = jwt.getExpiresAt();
    return expiry != null && clock.instant().isBefore(expiry)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "The token has expired", null));
  }

  private static boolean isRole(String role) {
    for (StaffRole value : StaffRole.values()) {
      if (value.name().equals(role)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the staff claims from a validated token.
   *
   * @param jwt the token
   * @return its claims
   */
  public static StaffClaims claims(Jwt jwt) {
    return new StaffClaims(
        UUID.fromString(required(jwt.getSubject(), JwtClaimNames.SUB)),
        UUID.fromString(required(jwt.getClaimAsString(INSTITUTION_ID), INSTITUTION_ID)),
        StaffRole.valueOf(required(jwt.getClaimAsString(ROLE), ROLE)),
        jwt.getClaimAsString(FIRST_NAME),
        jwt.getClaimAsString(EMAIL),
        ((Number) required(jwt.getClaim(TOKEN_VERSION), TOKEN_VERSION)).longValue(),
        UUID.fromString(required(jwt.getClaimAsString(SESSION_ID), SESSION_ID)));
  }

  private static <T> T required(T value, String claim) {
    if (value == null) {
      throw new IllegalArgumentException("the access token has no " + claim + " claim");
    }
    return value;
  }
}
