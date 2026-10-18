package io.github.mariusbayizere.fraudshield.auth.persistence;

import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * JPA mapping of {@code users} (ADR 0071). Only the staff-identity adapters use it; the rest of the
 * code sees the framework-free {@link StaffAccount}. {@code version} is the JPA optimistic lock:
 * entity updates (administrator edits) increment it; sign-in bookkeeping uses bulk statements that
 * do not. {@code updated_at} is maintained by a database trigger and not mapped.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
public class StaffUserEntity {

  @Id private UUID id;

  @Column(name = "institution_id", nullable = false, updatable = false)
  private UUID institutionId;

  @Column(name = "first_name", nullable = false)
  private String firstName;

  @Column(name = "last_name", nullable = false)
  private String lastName;

  @Column(nullable = false, updatable = false)
  private String email;

  private String phone;

  @Column(name = "password_hash")
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private StaffRole role;

  @Enumerated(EnumType.STRING)
  @Column(name = "requested_role")
  private StaffRole requestedRole;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountStatus status;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "token_version", nullable = false)
  private long tokenVersion;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Enumerated(EnumType.STRING)
  @Column(name = "preferred_locale", nullable = false)
  private StaffLocale preferredLocale;

  @Column(name = "avatar_url")
  private String avatarUrl;

  @Column(name = "oauth_provider")
  private String oauthProvider;

  @Column(name = "oauth_id")
  private String oauthId;

  @Column(name = "employee_id", nullable = false)
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Department department;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private long version;

  /** For JPA. */
  protected StaffUserEntity() {}

  /**
   * A new account row.
   *
   * @param account the account (ID, names, contact, role, status, locale, employee ID, department,
   *     avatar and creation time are used)
   * @param passwordHash bcrypt hash, or null for a Google-only account
   * @param googleSubject Google subject, or null
   * @return the entity to persist
   */
  public static StaffUserEntity create(
      StaffAccount account, String passwordHash, String googleSubject) {
    StaffUserEntity entity = new StaffUserEntity();
    entity.id = account.id();
    entity.institutionId = account.institutionId();
    entity.firstName = account.firstName();
    entity.lastName = account.lastName();
    entity.email = account.email().toLowerCase(java.util.Locale.ROOT);
    entity.phone = account.phone();
    entity.passwordHash = passwordHash;
    entity.role = account.role();
    entity.requestedRole = account.requestedRole();
    entity.status = account.status();
    entity.emailVerified = account.emailVerified();
    entity.preferredLocale = account.preferredLocale();
    entity.avatarUrl = account.avatarUrl();
    entity.oauthProvider = googleSubject == null ? null : "GOOGLE";
    entity.oauthId = googleSubject;
    entity.employeeId = account.employeeId();
    entity.department = account.department();
    entity.createdAt = account.createdAt();
    return entity;
  }

  /**
   * The domain view, without credential material.
   *
   * @return the account
   */
  public StaffAccount toDomain() {
    return new StaffAccount(
        id,
        institutionId,
        firstName,
        lastName,
        email,
        phone,
        role,
        requestedRole,
        status,
        lockedUntil,
        failedLoginCount,
        tokenVersion,
        emailVerified,
        preferredLocale,
        avatarUrl,
        oauthProvider != null,
        employeeId,
        department,
        lastLoginAt,
        createdAt,
        version);
  }

  /**
   * Applies an administrator's edit to the editable fields.
   *
   * @param firstName first name
   * @param lastName last name
   * @param phone phone
   * @param department department
   * @param role role
   * @param status status
   * @param preferredLocale locale
   * @param lockedUntil lock end
   */
  public void edit(
      String firstName,
      String lastName,
      String phone,
      Department department,
      StaffRole role,
      AccountStatus status,
      StaffLocale preferredLocale,
      Instant lockedUntil) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.phone = phone;
    this.department = department;
    this.role = role;
    this.status = status;
    this.preferredLocale = preferredLocale;
    this.lockedUntil = lockedUntil;
  }

  /** Clears the failure count (an administrator's reactivation or unlock). */
  public void clearFailures() {
    this.failedLoginCount = 0;
  }

  /** Increments the token version (D-27). */
  public void bumpTokenVersion() {
    this.tokenVersion++;
  }

  public UUID getId() {
    return id;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getOauthId() {
    return oauthId;
  }

  public long getTokenVersion() {
    return tokenVersion;
  }

  public long getVersion() {
    return version;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public StaffRole getRole() {
    return role;
  }
}
