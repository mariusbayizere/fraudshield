package io.github.mariusbayizere.fraudshield.admin.support;

import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The contract's StaffReference (user ID, names and role) of an account of the current tenant. */
public final class StaffReferences {

  private final StaffAccountRepository accounts;

  /**
   * Creates the lookup.
   *
   * @param accounts account repository
   */
  public StaffReferences(StaffAccountRepository accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
  }

  /**
   * The reference of an account. Must run in a tenant transaction.
   *
   * @param userId account
   * @return the reference, or null if the account is not visible
   */
  public Map<String, Object> of(UUID userId) {
    return accounts
        .findById(userId)
        .<Map<String, Object>>map(
            account -> {
              Map<String, Object> reference = new LinkedHashMap<>();
              reference.put("user_id", account.id());
              reference.put("first_name", account.firstName());
              reference.put("last_name", account.lastName());
              reference.put("role", account.role().name());
              return reference;
            })
        .orElse(null);
  }
}
