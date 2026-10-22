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
 * @param inBand how the credential is carried in band, or null: {@code stomp-connect} for the
 *     real-time channel, whose handshake authenticates no one and whose roles are enforced at the
 *     STOMP CONNECT (ADR 0115 point 9). Never public anywhere else.
 */
public record Access(
    Set<StaffRole> roles,
    Set<ApiKeyScope> scopes,
    boolean isPublic,
    boolean refreshCookie,
    String inBand) {

  /** Copies the sets and checks exactly one kind of access is declared. */
  public Access {
    roles = Set.copyOf(roles);
    scopes = Set.copyOf(scopes);
    if (inBand != null && (roles.isEmpty() || !scopes.isEmpty() || isPublic || refreshCookie)) {
      throw new IllegalArgumentException(
          "in-band access takes roles, and no scopes, public or refresh cookie");
    }
    int kinds =
        (roles.isEmpty() ? 0 : 1)
            + (scopes.isEmpty() ? 0 : 1)
            + (isPublic ? 1 : 0)
            + (refreshCookie ? 1 : 0);
    if (kinds != 1) {
      throw new IllegalArgumentException("an operation declares exactly one kind of access");
    }
  }

  /**
   * Access without an in-band credential.
   *
   * @param roles roles
   * @param scopes scopes
   * @param isPublic public
   * @param refreshCookie refresh cookie
   */
  public Access(
      Set<StaffRole> roles, Set<ApiKeyScope> scopes, boolean isPublic, boolean refreshCookie) {
    this(roles, scopes, isPublic, refreshCookie, null);
  }
}
