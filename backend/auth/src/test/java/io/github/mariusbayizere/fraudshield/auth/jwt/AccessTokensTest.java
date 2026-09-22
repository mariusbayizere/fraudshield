package io.github.mariusbayizere.fraudshield.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.github.mariusbayizere.fraudshield.auth.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/** RS256 access tokens: claims, 15-minute expiry, key rotation (FR-07-04, D-27). */
@Tag("FR-07-04")
class AccessTokensTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:00:00Z"));

  private static RSAKey key(String kid) throws Exception {
    return new RSAKeyGenerator(2048).keyID(kid).generate();
  }

  private static StaffClaims claims() {
    return new StaffClaims(
        UUID.randomUUID(),
        UUID.randomUUID(),
        StaffRole.SENIOR_ANALYST,
        "Aline",
        "aline@bank.rw",
        3,
        UUID.randomUUID());
  }

  @Test
  void issuesRs256TokensWithTheContractClaimsAndVerifiesThem() throws Exception {
    AccessTokens tokens =
        new AccessTokens(
            new SigningKeys(List.of(key("k1"))),
            "fraudshield",
            "staff",
            Duration.ofMinutes(15),
            clock);
    StaffClaims claims = claims();
    Jwt jwt = tokens.decoder(List.of()).decode(tokens.issue(claims));
    assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", "k1");
    assertThat(jwt.getClaims())
        .containsKeys(
            "sub",
            "role",
            "first_name",
            "email",
            "institution_id",
            "token_version",
            "sid",
            "exp",
            "iat");
    assertThat(AccessTokens.claims(jwt)).isEqualTo(claims);
    assertThat(tokens.ttlSeconds()).isEqualTo(900);
  }

  @Test
  void tokensExpireAfterFifteenMinutes() throws Exception {
    AccessTokens tokens =
        new AccessTokens(
            new SigningKeys(List.of(key("k1"))),
            "fraudshield",
            "staff",
            Duration.ofMinutes(15),
            clock);
    String token = tokens.issue(claims());
    clock.advance(Duration.ofMinutes(15).minusMillis(1));
    assertThat(tokens.decoder(List.of()).decode(token)).isNotNull();
    clock.advance(Duration.ofMillis(1));
    assertThatThrownBy(() -> tokens.decoder(List.of()).decode(token))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rotationKeepsOldTokensValidAndRefusesUnknownKeysIssuersAndAudiences() throws Exception {
    RSAKey old = key("k1");
    RSAKey next = key("k2");
    AccessTokens before =
        new AccessTokens(
            new SigningKeys(List.of(old)), "fraudshield", "staff", Duration.ofMinutes(15), clock);
    AccessTokens after =
        new AccessTokens(
            new SigningKeys(List.of(next, old)),
            "fraudshield",
            "staff",
            Duration.ofMinutes(15),
            clock);
    String oldToken = before.issue(claims());
    assertThat(after.decoder(List.of()).decode(oldToken)).isNotNull();
    assertThat(after.decoder(List.of()).decode(after.issue(claims())).getHeaders())
        .containsEntry("kid", "k2");
    assertThatThrownBy(() -> before.decoder(List.of()).decode(after.issue(claims())))
        .as("signed by a key the verifier does not hold")
        .isInstanceOf(JwtException.class);
    AccessTokens otherIssuer =
        new AccessTokens(
            new SigningKeys(List.of(old)), "someone", "staff", Duration.ofMinutes(15), clock);
    assertThatThrownBy(() -> before.decoder(List.of()).decode(otherIssuer.issue(claims())))
        .isInstanceOf(JwtException.class);
    AccessTokens otherAudience =
        new AccessTokens(
            new SigningKeys(List.of(old)), "fraudshield", "other", Duration.ofMinutes(15), clock);
    assertThatThrownBy(() -> before.decoder(List.of()).decode(otherAudience.issue(claims())))
        .isInstanceOf(JwtException.class);
  }

  @Test
  @Tag("D-27")
  void extraValidatorsRunAfterTheSignatureCheck() throws Exception {
    AccessTokens tokens =
        new AccessTokens(
            new SigningKeys(List.of(key("k1"))),
            "fraudshield",
            "staff",
            Duration.ofMinutes(15),
            clock);
    String token = tokens.issue(claims());
    assertThatThrownBy(
            () ->
                tokens
                    .decoder(
                        List.of(
                            jwt ->
                                OAuth2TokenValidatorResult.failure(
                                    new org.springframework.security.oauth2.core.OAuth2Error(
                                        "invalid_token"))))
                    .decode(token))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void refusesWeakOrMissingKeysAndPublishesOnlyPublicParts() throws Exception {
    assertThatThrownBy(() -> new SigningKeys(List.of())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new SigningKeys(List.of(new RSAKeyGenerator(1024, true).keyID("weak").generate())))
        .isInstanceOf(IllegalStateException.class);
    SigningKeys keys = new SigningKeys(List.of(key("k1")));
    assertThat(keys.publicJwks().toString()).doesNotContain("\"d\"").contains("k1");
    assertThat(keys.active().getKeyID()).isEqualTo("k1");
  }
}
