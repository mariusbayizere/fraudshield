package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyPrincipal;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** An authenticated API key; its authorities are {@code SCOPE_<scope>}, never a role. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final transient ApiKeyPrincipal principal;

  /**
   * Creates the authentication.
   *
   * @param principal the key
   */
  public ApiKeyAuthentication(ApiKeyPrincipal principal) {
    super(authorities(principal));
    this.principal = principal;
    setAuthenticated(true);
  }

  private static List<GrantedAuthority> authorities(ApiKeyPrincipal principal) {
    return principal.scopes().stream()
        .map(ApiKeyScope::value)
        .sorted()
        .<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
        .toList();
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public ApiKeyPrincipal getPrincipal() {
    return principal;
  }
}
