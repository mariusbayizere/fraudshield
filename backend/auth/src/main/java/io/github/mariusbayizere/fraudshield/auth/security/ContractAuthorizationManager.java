package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyScope;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Applies {@link ContractPolicy} to every request (FR-07-01, FR-07-05, ADR 0014): a staff operation
 * needs a staff token whose role is listed, a machine operation an API key with a listed scope; an
 * API key never passes a role check and a staff token never passes a scope check. Anything the
 * contract does not declare is denied.
 */
public final class ContractAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

  private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);
  private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

  private final ContractPolicy policy;

  /**
   * Creates the manager.
   *
   * @param policy the contract policy
   */
  public ContractAuthorizationManager(ContractPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    String path =
        context
            .getRequest()
            .getRequestURI()
            .substring(context.getRequest().getContextPath().length());
    Optional<ContractPolicy.Operation> operation =
        policy.match(context.getRequest().getMethod(), path);
    if (operation.isEmpty()) {
      return DENIED;
    }
    Access access = operation.get().access();
    if (access.isPublic() || access.refreshCookie()) {
      return GRANTED;
    }
    Authentication caller = authentication.get();
    if (caller == null || !caller.isAuthenticated()) {
      return DENIED;
    }
    if (!access.roles().isEmpty()) {
      return caller instanceof JwtAuthenticationToken && hasRole(caller, access) ? GRANTED : DENIED;
    }
    return caller instanceof ApiKeyAuthentication && hasScope(caller, access) ? GRANTED : DENIED;
  }

  private static boolean hasRole(Authentication caller, Access access) {
    for (StaffRole role : access.roles()) {
      if (has(caller, "ROLE_" + role.name())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasScope(Authentication caller, Access access) {
    for (ApiKeyScope scope : access.scopes()) {
      if (has(caller, "SCOPE_" + scope.value())) {
        return true;
      }
    }
    return false;
  }

  private static boolean has(Authentication caller, String authority) {
    for (GrantedAuthority granted : caller.getAuthorities()) {
      if (authority.equals(granted.getAuthority())) {
        return true;
      }
    }
    return false;
  }
}
