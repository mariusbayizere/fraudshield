package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads and writes {@code users} (D-31). Every method except the two pre-tenant lookups must run in
 * a tenant transaction ({@code TenantTransactions}); row-level security confines it to the
 * institution of that transaction.
 */
public final class StaffAccountRepository {

  /** End of an administrator's lock, which never lifts by itself. */
  public static final Instant ADMIN_LOCK_UNTIL = Instant.parse("9999-12-31T23:59:59Z");

  private static final String COLUMNS =
      """
      id, institution_id, first_name, last_name, email, phone, role, requested_role, status,
      locked_until, failed_login_count, token_version, email_verified, preferred_locale, avatar_url,
      oauth_provider, employee_id, department, last_login_at, created_at, version
      """;

  private static final RowMapper<StaffAccount> MAPPER = StaffAccountRepository::map;

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template of the application role
   */
  public StaffAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
    return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", MAPPER, id).stream()
        .findFirst();
  }

  /**
   * An account of the current institution, locked for update.
   *
   * @param id account ID
   * @return the account
   */
  public Optional<StaffAccount> findByIdForUpdate(UUID id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM users WHERE id = ? FOR UPDATE", MAPPER, id)
        .stream()
        .findFirst();
  }

  /**
   * Whether an employee ID is taken in the current institution.
   *
   * @param employeeId employee ID
   * @return whether it is taken
   */
  public boolean employeeIdTaken(String employeeId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE employee_id = ?)",
            Boolean.class,
            employeeId));
  }

  /**
   * The bcrypt hash of an account's password.
   *
   * @param id account ID
   * @return the hash, or empty for a Google-only account
   */
  public Optional<String> passwordHash(UUID id) {
    return jdbc
        .query("SELECT password_hash FROM users WHERE id = ?", (row, i) -> row.getString(1), id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Inserts an account.
   *
   * @param account the account (ID, names, contact, role, status, locale, employee ID, department,
   *     avatar and Google link are used)
   * @param passwordHash bcrypt hash, or null for a Google-only account
   * @param googleSubject Google {@code sub}, or null
   */
  public void insert(StaffAccount account, String passwordHash, String googleSubject) {
    jdbc.update(
        """
        INSERT INTO users (id, institution_id, first_name, last_name, email, phone, password_hash,
          role, requested_role, status, email_verified, preferred_locale, avatar_url, oauth_provider,
          oauth_id, employee_id, department)
        VALUES (?, ?, ?, ?, lower(?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        account.id(),
        account.institutionId(),
        account.firstName(),
        account.lastName(),
        account.email(),
        account.phone(),
        passwordHash,
        account.role().name(),
        account.requestedRole() == null ? null : account.requestedRole().name(),
        account.status().name(),
        account.emailVerified(),
        account.preferredLocale().name(),
        account.avatarUrl(),
        googleSubject == null ? null : "GOOGLE",
        googleSubject,
        account.employeeId(),
        account.department().name());
  }

  /**
   * Records a successful sign-in: clears failures and any lock.
   *
   * @param id account ID
   * @param at sign-in time
   */
  public void recordLoginSuccess(UUID id, Instant at) {
    jdbc.update(
        """
        UPDATE users SET failed_login_count = 0, locked_until = NULL, last_login_at = ?,
          status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END
        WHERE id = ?
        """,
        Timestamp.from(at),
        id);
  }

  /**
   * Counts a failed sign-in.
   *
   * @param id account ID
   * @return the new consecutive failure count
   */
  public int recordLoginFailure(UUID id) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "UPDATE users SET failed_login_count = failed_login_count + 1 WHERE id = ?"
                + " RETURNING failed_login_count",
            Integer.class,
            id));
  }

  /**
   * Locks an account.
   *
   * @param id account ID
   * @param until end of the lock
   */
  public void lock(UUID id, Instant until) {
    jdbc.update(
        "UPDATE users SET status = 'LOCKED', locked_until = ? WHERE id = ?",
        Timestamp.from(until),
        id);
  }

  /**
   * Lifts a lock and clears the failure count.
   *
   * @param id account ID
   */
  public void unlock(UUID id) {
    jdbc.update(
        """
        UPDATE users SET status = 'ACTIVE', locked_until = NULL, failed_login_count = 0
        WHERE id = ? AND status = 'LOCKED'
        """,
        id);
  }

  /**
   * Replaces the password, ends every session (D-27, FR-07-09) and lifts a failure lock.
   *
   * @param id account ID
   * @param passwordHash new bcrypt hash
   * @return the new token version
   */
  public long changePassword(UUID id, String passwordHash) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            """
            UPDATE users SET password_hash = ?, token_version = token_version + 1,
              failed_login_count = 0,
              status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END,
              locked_until = CASE WHEN status = 'LOCKED' THEN NULL ELSE locked_until END
            WHERE id = ? RETURNING token_version
            """,
            Long.class,
            passwordHash,
            id));
  }

  /**
   * Increments the token version, which ends every access token of the account (D-27).
   *
   * @param id account ID
   * @return the new token version
   */
  public long bumpTokenVersion(UUID id) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "UPDATE users SET token_version = token_version + 1 WHERE id = ?"
                + " RETURNING token_version",
            Long.class,
            id));
  }

  /**
   * The current token version, for the session check.
   *
   * @param id account ID
   * @return the version, or empty if the account does not exist in this institution
   */
  public Optional<Long> tokenVersion(UUID id) {
    return jdbc
        .query("SELECT token_version FROM users WHERE id = ?", (row, i) -> row.getLong(1), id)
        .stream()
        .findFirst();
  }

  /**
   * Marks the email verified.
   *
   * @param id account ID
   */
  public void markEmailVerified(UUID id) {
    jdbc.update("UPDATE users SET email_verified = true WHERE id = ?", id);
  }

  /**
   * Links a Google account and stores the Google avatar (FR-07-03).
   *
   * @param id account ID
   * @param googleSubject Google {@code sub}
   * @param avatarUrl avatar URL, or null
   */
  public void linkGoogle(UUID id, String googleSubject, String avatarUrl) {
    jdbc.update(
        """
        UPDATE users SET oauth_provider = 'GOOGLE', oauth_id = ?,
          avatar_url = COALESCE(?, avatar_url), email_verified = true
        WHERE id = ?
        """,
        googleSubject,
        avatarUrl,
        id);
  }

  /**
   * The Google subject linked to an account.
   *
   * @param id account ID
   * @return the subject, or empty
   */
  public Optional<String> googleSubject(UUID id) {
    return jdbc
        .query("SELECT oauth_id FROM users WHERE id = ?", (row, i) -> row.getString(1), id)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Applies an administrator's edit. The version trigger (V12) advances {@code version} when an
   * editable field changes; a role or status change also increments the token version.
   *
   * @param id account ID
   * @param edit the new values
   */
  public void applyAdminEdit(UUID id, AdminEdit edit) {
    jdbc.update(
        """
        UPDATE users SET first_name = ?, last_name = ?, phone = ?, department = ?, role = ?,
          status = ?, preferred_locale = ?, locked_until = ?,
          failed_login_count = CASE WHEN ? THEN 0 ELSE failed_login_count END,
          token_version = token_version + CASE WHEN ? THEN 1 ELSE 0 END
        WHERE id = ?
        """,
        edit.firstName(),
        edit.lastName(),
        edit.phone(),
        edit.department().name(),
        edit.role().name(),
        edit.status().name(),
        edit.preferredLocale().name(),
        edit.lockedUntil() == null ? null : Timestamp.from(edit.lockedUntil()),
        edit.status() == AccountStatus.ACTIVE,
        edit.endsSessions(),
        id);
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
   * every ACTIVE administrator row of the institution first, so two administrators demoting each
   * other at the same moment are serialised and cannot both succeed.
   *
   * @param excluding account to leave out
   * @return the count
   */
  public long otherActiveAdmins(UUID excluding) {
    List<UUID> admins =
        jdbc.queryForList(
            "SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'"
                + " ORDER BY id FOR UPDATE",
            UUID.class);
    return admins.stream().filter(id -> !id.equals(excluding)).count();
  }

  /**
   * A page of accounts, newest first, with keyset pagination.
   *
   * @param status filter, or null
   * @param role filter, or null
   * @param after position after which to start, or null for the first page
   * @param limit page size
   * @return up to {@code limit + 1} accounts, so the caller knows whether another page exists
   */
  public List<StaffAccount> page(AccountStatus status, StaffRole role, Position after, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM users WHERE true");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (role != null) {
      sql.append(" AND role = ?");
      args.add(role.name());
    }
    if (after != null) {
      sql.append(" AND (created_at, id) < (?, ?)");
      args.add(Timestamp.from(after.createdAt()));
      args.add(after.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    args.add(limit + 1);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
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
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM users WHERE status = 'PENDING_APPROVAL' ORDER BY created_at, id",
        MAPPER);
  }

  /**
   * An account's ID and institution.
   *
   * @param userId account ID
   * @param institutionId institution
   */
  public record AccountRef(UUID userId, UUID institutionId) {}

  private static StaffAccount map(ResultSet row, int index) throws SQLException {
    String requested = row.getString("requested_role");
    return new StaffAccount(
        row.getObject("id", UUID.class),
        row.getObject("institution_id", UUID.class),
        row.getString("first_name"),
        row.getString("last_name"),
        row.getString("email"),
        row.getString("phone"),
        StaffRole.valueOf(row.getString("role")),
        requested == null ? null : StaffRole.valueOf(requested),
        AccountStatus.valueOf(row.getString("status")),
        instant(row, "locked_until"),
        row.getInt("failed_login_count"),
        row.getLong("token_version"),
        row.getBoolean("email_verified"),
        StaffLocale.valueOf(row.getString("preferred_locale")),
        row.getString("avatar_url"),
        row.getString("oauth_provider") != null,
        row.getString("employee_id"),
        Department.valueOf(row.getString("department")),
        instant(row, "last_login_at"),
        instant(row, "created_at"),
        row.getLong("version"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
