package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserJpaRepository;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes {@code users} (D-31) for the staff-identity services.
 *
 * <p>Hybrid persistence (ADR 0071):
 *
 * <ul>
 *   <li>Tenant-scoped access goes through JPA ({@link StaffUserJpaRepository}). Every method except
 *       the three pre-tenant lookups must run in a tenant transaction ({@code TenantTransactions}),
 *       whose {@code set_config} applies to Hibernate's statements because they share the
 *       transaction's connection. Row-level security then confines them to one institution.
 *   <li>The pre-tenant lookups call SECURITY DEFINER functions in explicit SQL, because they run
 *       before an institution is known.
 * </ul>
 *
 * <p>Writes flush at once, so explicit-SQL statements interleaved in the same transaction (refresh
 * tokens, verification tokens, audit rows) always see them, and in the order the code states.
 */
public final class StaffAccountRepository {

  /** End of an administrator's lock, which never lifts by itself. */
  public static final Instant ADMIN_LOCK_UNTIL = Instant.parse("9999-12-31T23:59:59Z");

  private final JdbcTemplate jdbc;
  private final StaffUserJpaRepository users;
  private final EntityManager entities;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template of the application role (pre-tenant lookups)
   * @param users Spring Data repository of users
   * @param entities shared, transaction-bound entity manager
   */
  public StaffAccountRepository(
      JdbcTemplate jdbc, StaffUserJpaRepository users, EntityManager entities) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.users = Objects.requireNonNull(users, "users");
    this.entities = Objects.requireNonNull(entities, "entities");
  }

  /**
   * The institution of an email, before any tenant is known (SECURITY DEFINER lookup, V2).
   *
   * @param email email, any case
   * @return the account and its institution
   */
  public Optional<AccountRef> findRefByEmail(String email) {
    return jdbc
        .query(
            "SELECT user_id, institution_id FROM auth_find_user_by_email(?)",
            (row, i) -> new AccountRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            email)
        .stream()
        .findFirst();
  }

  /**
   * The institution of an account ID, before any tenant is known (V12).
   *
   * @param userId account ID
   * @return the institution
   */
  public Optional<UUID> findInstitution(UUID userId) {
    return Optional.ofNullable(
        jdbc.queryForObject("SELECT auth_find_user_institution(?)", UUID.class, userId));
  }

  /**
   * Whether an employee ID is registered in any institution (V12).
   *
   * @param employeeId employee ID
   * @return whether it is taken
   */
  public boolean employeeIdRegisteredAnywhere(String employeeId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT auth_employee_id_registered(?)", Boolean.class, employeeId));
  }

  /**
   * An account of the current institution.
   *
   * @param id account ID
   * @return the account
   */
  public Optional<StaffAccount> findById(UUID id) {
    return users.findById(id).map(StaffUserEntity::toDomain);
  }

  /**
   * An account of the current institution, locked for update.
   *
   * @param id account ID
   * @return the account
   */
  public Optional<StaffAccount> findByIdForUpdate(UUID id) {
    return lockCurrent(id).map(StaffUserEntity::toDomain);
  }

  /**
   * Locks the row and loads its state as of the lock ({@code SELECT … FOR UPDATE} through {@code
   * refresh}). The account row is the first lock of every session write (ADR 0070, review finding
   * 1).
   *
   * <p>A locking query is not enough when the transaction already holds the entity (the acting
   * administrator editing themself): Hibernate either hands back the copy read before the lock, or
   * refuses the row outright when its version moved in between. Refreshing with the lock replaces
   * the held state, version included, so every decision after the lock sees the locked row and a
   * flush cannot write stale state back (ADR 0071 §4).
   */
  private Optional<StaffUserEntity> lockCurrent(UUID id) {
    StaffUserEntity entity = entities.find(StaffUserEntity.class, id);
    if (entity == null) {
      return Optional.empty();
    }
    entities.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    return Optional.of(entity);
  }

  /**
   * Whether an employee ID is taken in the current institution.
   *
   * @param employeeId employee ID
   * @return whether it is taken
   */
  public boolean employeeIdTaken(String employeeId) {
    return users.existsByEmployeeId(employeeId);
  }

  /**
   * The account of the current institution with an employee ID.
   *
   * @param employeeId employee ID
   * @return the account
   */
  public Optional<StaffAccount> findByEmployeeId(String employeeId) {
    return users.findFirstByEmployeeId(employeeId).map(StaffUserEntity::toDomain);
  }

  /**
   * The bcrypt hash of an account's password.
   *
   * @param id account ID
   * @return the hash, or empty for a Google-only account
   */
  public Optional<String> passwordHash(UUID id) {
    return users.findById(id).map(StaffUserEntity::getPasswordHash);
  }

  /**
   * Inserts an account.
   *
   * @param account the account (ID, names, contact, role, status, locale, employee ID, department,
   *     avatar, Google link and creation time are used)
   * @param passwordHash bcrypt hash, or null for a Google-only account
   * @param googleSubject Google {@code sub}, or null
   */
  public void insert(StaffAccount account, String passwordHash, String googleSubject) {
    entities.persist(StaffUserEntity.create(account, passwordHash, googleSubject));
    entities.flush();
  }

  /**
   * Records a successful sign-in: clears failures and any lock.
   *
   * @param id account ID
   * @param at sign-in time
   */
  public void recordLoginSuccess(UUID id, Instant at) {
    users.recordLoginSuccess(id, at);
  }

  /**
   * Counts a failed sign-in.
   *
   * @param id account ID
   * @return the new consecutive failure count
   */
  public int recordLoginFailure(UUID id) {
    users.incrementFailures(id);
    return users.findById(id).map(e -> e.toDomain().failedLoginCount()).orElseThrow();
  }

  /**
   * Locks an account.
   *
   * @param id account ID
   * @param until end of the lock
   */
  public void lock(UUID id, Instant until) {
    users.lock(id, until);
  }

  /**
   * Lifts a lock and clears the failure count.
   *
   * @param id account ID
   */
  public void unlock(UUID id) {
    users.unlock(id);
  }

  /**
   * Replaces the password, ends every session (D-27, FR-07-09) and lifts a failure lock.
   *
   * @param id account ID
   * @param passwordHash new bcrypt hash
   * @return the new token version
   */
  public long changePassword(UUID id, String passwordHash) {
    users.changePassword(id, passwordHash);
    return tokenVersion(id).orElseThrow();
  }

  /**
   * Increments the token version, which ends every access token of the account (D-27).
   *
   * @param id account ID
   * @return the new token version
   */
  public long bumpTokenVersion(UUID id) {
    users.bumpTokenVersion(id);
    return tokenVersion(id).orElseThrow();
  }

  /**
   * The current token version, for the session check.
   *
   * @param id account ID
   * @return the version, or empty if the account does not exist in this institution
   */
  public Optional<Long> tokenVersion(UUID id) {
    return users.findById(id).map(StaffUserEntity::getTokenVersion);
  }

  /**
   * Marks the email verified.
   *
   * @param id account ID
   */
  public void markEmailVerified(UUID id) {
    users.markEmailVerified(id);
  }

  /**
   * Links a Google account and stores the Google avatar (FR-07-03).
   *
   * @param id account ID
   * @param googleSubject Google {@code sub}
   * @param avatarUrl avatar URL, or null
   */
  public void linkGoogle(UUID id, String googleSubject, String avatarUrl) {
    users.linkGoogle(id, googleSubject, avatarUrl);
  }

  /**
   * The Google subject linked to an account.
   *
   * @param id account ID
   * @return the subject, or empty
   */
  public Optional<String> googleSubject(UUID id) {
    return users.findById(id).map(StaffUserEntity::getOauthId);
  }

  /**
   * Applies an administrator's edit through the entity, so the JPA {@code @Version} advances when
   * (and only when) a field changes. A role or status change also increments the token version.
   * Flushes at once, so the returned account and the caller's next statements see the new version.
   *
   * @param id account ID
   * @param edit the new values
   */
  public void applyAdminEdit(UUID id, AdminEdit edit) {
    StaffUserEntity entity = lockCurrent(id).orElseThrow();
    entity.edit(
        edit.firstName(),
        edit.lastName(),
        edit.phone(),
        edit.department(),
        edit.role(),
        edit.status(),
        edit.preferredLocale(),
        edit.lockedUntil());
    if (edit.status() == AccountStatus.ACTIVE) {
      entity.clearFailures();
    }
    if (edit.endsSessions()) {
      entity.bumpTokenVersion();
    }
    entities.flush();
  }

  /**
   * Values of an administrator's edit.
   *
   * @param firstName first name
   * @param lastName last name
   * @param phone phone, or null
   * @param department department
   * @param role role
   * @param status status
   * @param preferredLocale locale
   * @param lockedUntil lock end, required for LOCKED
   * @param endsSessions whether the edit increments the token version
   */
  public record AdminEdit(
      String firstName,
      String lastName,
      String phone,
      Department department,
      StaffRole role,
      AccountStatus status,
      StaffLocale preferredLocale,
      Instant lockedUntil,
      boolean endsSessions) {}

  /**
   * Number of ACTIVE administrators other than one account (the last-active-admin rule). Locks
   * every ACTIVE administrator row of the institution first, in ID order, so two administrators
   * demoting each other at the same moment are serialised and cannot both succeed.
   *
   * @param excluding account to leave out
   * @return the count
   */
  public long otherActiveAdmins(UUID excluding) {
    return users.lockActiveAdmins().stream().filter(u -> !u.getId().equals(excluding)).count();
  }

  /**
   * A page of accounts, newest first, with keyset pagination: one query.
   *
   * @param status filter, or null
   * @param role filter, or null
   * @param after position after which to start, or null for the first page
   * @param limit page size
   * @return up to {@code limit + 1} accounts, so the caller knows whether another page exists
   */
  public List<StaffAccount> page(AccountStatus status, StaffRole role, Position after, int limit) {
    StringBuilder jpql = new StringBuilder("select u from StaffUserEntity u where 1 = 1");
    if (status != null) {
      jpql.append(" and u.status = :status");
    }
    if (role != null) {
      jpql.append(" and u.role = :role");
    }
    if (after != null) {
      jpql.append(" and (u.createdAt < :afterAt or (u.createdAt = :afterAt and u.id < :afterId))");
    }
    jpql.append(" order by u.createdAt desc, u.id desc");
    TypedQuery<StaffUserEntity> query =
        entities.createQuery(jpql.toString(), StaffUserEntity.class);
    if (status != null) {
      query.setParameter("status", status);
    }
    if (role != null) {
      query.setParameter("role", role);
    }
    if (after != null) {
      query.setParameter("afterAt", after.createdAt());
      query.setParameter("afterId", after.id());
    }
    return query.setMaxResults(limit + 1).getResultList().stream()
        .map(StaffUserEntity::toDomain)
        .toList();
  }

  /**
   * A keyset pagination position.
   *
   * @param createdAt creation time of the last account of the previous page
   * @param id its ID
   */
  public record Position(Instant createdAt, UUID id) {}

  /**
   * Accounts awaiting approval, oldest first.
   *
   * @return the accounts
   */
  public List<StaffAccount> pendingApproval() {
    return users.findByStatusOrderByCreatedAtAscIdAsc(AccountStatus.PENDING_APPROVAL).stream()
        .map(StaffUserEntity::toDomain)
        .toList();
  }

  /**
   * An account's ID and institution.
   *
   * @param userId account ID
   * @param institutionId institution
   */
  public record AccountRef(UUID userId, UUID institutionId) {}
}
