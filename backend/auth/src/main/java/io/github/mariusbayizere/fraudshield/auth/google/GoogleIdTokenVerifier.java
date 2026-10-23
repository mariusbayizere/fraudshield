package io.github.mariusbayizere.fraudshield.auth.google;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Verifies Google ID tokens server-side (FR-07-03, D-23): RS256 signature against Google's JWKS
 * (cached, refreshed on an unknown {@code kid}), {@code iss}, {@code aud} equal to our client ID,
 * {@code exp} in the future, and {@code email_verified} true.
 */
public final class GoogleIdTokenVerifier {

  private final DefaultJWTProcessor<SecurityContext> processor;
  private final String clientId;
  private final List<String> issuers;
  private final Clock clock;

  /**
   * Creates the verifier.
   *
   * @param jwksUri JWKS URI
   * @param clientId OAuth client ID (the required audience)
   * @param issuers accepted issuers
   * @param timeout JWKS fetch timeout
   * @param clock clock
   */
  public GoogleIdTokenVerifier(
      URI jwksUri, String clientId, List<String> issuers, Duration timeout, Clock clock) {
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.issuers = List.copyOf(issuers);
    this.clock = Objects.requireNonNull(clock, "clock");
    int millis = Math.toIntExact(timeout.toMillis());
    JWKSource<SecurityContext> keys;
    try {
      keys =
          JWKSourceBuilder.create(
                  jwksUri.toURL(), new DefaultResourceRetriever(millis, millis, 256 * 1024))
              .build();
    } catch (MalformedURLException e) {
      throw new IllegalStateException("fraudshield.auth.google.jwks-uri is not a URL", e);
    }
    processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
  }

  /**
   * Verifies an ID token.
   *
   * @param idToken the compact JWT
   * @return the identity, or empty if any check fails
   * @throws GoogleUnavailableException if Google's keys cannot be fetched
   */
  public Optional<GoogleIdentity> verify(String idToken) {
    JWTClaimsSet claims;
    try {
      claims = processor.process(idToken, null);
    } catch (RemoteKeySourceException e) {
      throw new GoogleUnavailableException("Google's signing keys are unreachable", e);
    } catch (ParseException | BadJOSEException | com.nimbusds.jose.JOSEException e) {
      return Optional.empty();
    }
    try {
      Date expiry = claims.getExpirationTime();
      boolean valid =
          issuers.contains(claims.getIssuer())
              && claims.getAudience() != null
              && claims.getAudience().contains(clientId)
              && expiry != null
              && expiry.toInstant().isAfter(clock.instant())
              && Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))
              && claims.getStringClaim("email") != null
              && claims.getSubject() != null;
      if (!valid) {
        return Optional.empty();
      }
      return Optional.of(
          new GoogleIdentity(
              claims.getSubject(),
              claims.getStringClaim("email").toLowerCase(Locale.ROOT),
              claims.getStringClaim("given_name"),
              claims.getStringClaim("family_name"),
              claims.getStringClaim("picture")));
    } catch (ParseException e) {
      return Optional.empty();
    }
  }
}
