package io.github.mariusbayizere.fraudshield.auth.google;

/**
 * Tokens from Google's token endpoint.
 *
 * @param idToken the ID token, verified separately
 * @param accessToken access token, held only to revoke it at sign-out (FR-07-09), or null
 * @param expiresInSeconds access-token lifetime
 */
public record GoogleTokens(String idToken, String accessToken, long expiresInSeconds) {

  @Override
  public String toString() {
    return "GoogleTokens[<redacted>]";
  }
}
