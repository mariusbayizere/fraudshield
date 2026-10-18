package io.github.mariusbayizere.fraudshield.auth.account;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Email domains allowed to self-register or create an account through Google sign-in, and the
 * institution each belongs to (D-23, D-24). A domain not listed here cannot create an account; its
 * users need an administrator.
 */
public final class SelfServiceDomains {

  private final Map<String, UUID> institutionsByDomain;

  /**
   * Creates the allowlist.
   *
   * @param institutionsByDomain lower-case domain to institution ID
   */
  public SelfServiceDomains(Map<String, UUID> institutionsByDomain) {
    this.institutionsByDomain =
        Map.copyOf(
            institutionsByDomain.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue)));
  }

  /**
   * The institution of an email's domain.
   *
   * @param email the email
   * @return the institution, or empty if the domain is not allowed
   */
  public Optional<UUID> institutionOf(String email) {
    int at = email.lastIndexOf('@');
    if (at < 0) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        institutionsByDomain.get(email.substring(at + 1).toLowerCase(Locale.ROOT)));
  }
}
