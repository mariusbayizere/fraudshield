package io.github.mariusbayizere.fraudshield.auth.google;

import java.util.Optional;

/** Google OAuth endpoints (port, D-51): a real HTTP adapter and a local fake in tests. */
public interface GoogleIdentityClient {

  /**
   * Exchanges an authorization code with its PKCE verifier.
   *
   * @param code authorization code
   * @param codeVerifier PKCE verifier
   * @param redirectUri redirect URI the code was issued for
   * @return the tokens, or empty if Google refuses the code
   * @throws GoogleUnavailableException if Google cannot be reached in time
   */
  Optional<GoogleTokens> exchange(String code, String codeVerifier, String redirectUri);

  /**
   * Revokes a token (FR-07-09). Failures are logged, never thrown: sign-out must not fail because
   * Google is unreachable.
   *
   * @param token access or refresh token
   * @return whether Google confirmed the revocation
   */
  boolean revoke(String token);
}
