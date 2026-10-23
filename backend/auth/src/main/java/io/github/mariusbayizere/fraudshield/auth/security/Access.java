package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.Set;

/**
 * Who may call one operation, as declared in the golden authorisation matrix (ADR 0014).
 *
 * @param roles staff roles (bearer JWT), empty unless a staff operation
 * @param scopes API-key scopes, empty unless a machine operation
 * @param isPublic whether no credential is needed
 * @param refreshCookie whether the refresh cookie is the credential
 */
public record Access(
    Set<StaffRole> roles, Set<ApiKeyScope> scopes, boolean isPublic, boolean refreshCookie) {

  /** Copies the sets and checks exactly one kind of access is declared. */
  public Access {
    roles = Set.copyOf(roles);
    scopes = Set.copyOf(scopes);
    int kinds =
        (roles.isEmpty() ? 0 : 1)
            + (scopes.isEmpty() ? 0 : 1)
            + (isPublic ? 1 : 0)
            + (refreshCookie ? 1 : 0);
    if (kinds != 1) {
      throw new IllegalArgumentException("an operation declares exactly one kind of access");
    }
  }
}
