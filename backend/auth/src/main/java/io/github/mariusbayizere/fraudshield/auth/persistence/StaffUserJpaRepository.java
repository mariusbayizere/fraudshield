package io.github.mariusbayizere.fraudshield.auth.persistence;

import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository of {@code users} (ADR 0071). Every query runs inside a tenant transaction,
 * so row-level security confines it to one institution. Bulk updates bypass the persistence
 * context; they flush before and clear after, so no managed entity is left stale. They advance the
 * optimistic version exactly when the status changes (a failure lock, an unlock, a sign-in or
 * password change that lifts a lock), as the removed V12 trigger did, and leave it alone for
 * bookkeeping (failure count, last sign-in, token version, email verification, Google link), so a
 * sign-in never makes an administrator's edit stale but a status change the administrator has not
 * seen does.
 */
public interface StaffUserJpaRepository extends JpaRepository<StaffUserEntity, UUID> {

  /**
   * The institution's ACTIVE administrators, locked in ID order.
   *
   * @return the administrators
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select u from StaffUserEntity u where u.role = io.github.mariusbayizere.fraudshield.common"
          + ".config.StaffRole.ADMIN and u.status = io.github.mariusbayizere.fraudshield.auth"
          + ".domain.AccountStatus.ACTIVE order by u.id")
  List<StaffUserEntity> lockActiveAdmins();

  /**
   * Whether an employee ID is taken in the institution.
   *
   * @param employeeId employee ID
   * @return whether it exists
   */
  boolean existsByEmployeeId(String employeeId);

  /**
   * The account with an employee ID.
   *
   * @param employeeId employee ID
   * @return the account
   */
  Optional<StaffUserEntity> findFirstByEmployeeId(String employeeId);

  /**
   * Accounts in a status, oldest first.
   *
   * @param status status
   * @return the accounts
   */
  List<StaffUserEntity> findByStatusOrderByCreatedAtAscIdAsc(AccountStatus status);

  /**
   * Records a successful sign-in: clears failures and any lock.
   *
   * @param id account
   * @param at sign-in time
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.failedLoginCount = 0, u.lockedUntil = null,"
          + " u.lastLoginAt = :at, u.version = u.version + case when u.status = io.github"
          + ".mariusbayizere.fraudshield.auth.domain.AccountStatus.LOCKED then 1 else 0 end,"
          + " u.status = case when u.status = io.github.mariusbayizere"
          + ".fraudshield.auth.domain.AccountStatus.LOCKED then io.github.mariusbayizere"
          + ".fraudshield.auth.domain.AccountStatus.ACTIVE else u.status end where u.id = :id")
  int recordLoginSuccess(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Counts a failed sign-in.
   *
   * @param id account
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.failedLoginCount = u.failedLoginCount + 1 where u.id = :id")
  int incrementFailures(@Param("id") UUID id);

  /**
   * Locks an account after failed sign-ins.
   *
   * @param id account
   * @param until end of the lock
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.status = io.github.mariusbayizere.fraudshield.auth.domain"
          + ".AccountStatus.LOCKED, u.lockedUntil = :until, u.version = u.version + case when"
          + " u.status = io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus.LOCKED"
          + " then 0 else 1 end where u.id = :id")
  int lock(@Param("id") UUID id, @Param("until") Instant until);

  /**
   * Lifts a lock and clears the failures.
   *
   * @param id account
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.status = io.github.mariusbayizere.fraudshield.auth.domain"
          + ".AccountStatus.ACTIVE, u.lockedUntil = null, u.failedLoginCount = 0,"
          + " u.version = u.version + 1 where u.id = :id and u.status = io.github.mariusbayizere"
          + ".fraudshield.auth.domain.AccountStatus.LOCKED")
  int unlock(@Param("id") UUID id);

  /**
   * Replaces the password, increments the token version and lifts a failure lock.
   *
   * @param id account
   * @param hash new bcrypt hash
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.passwordHash = :hash, u.tokenVersion = u.tokenVersion + 1,"
          + " u.failedLoginCount = 0, u.version = u.version + case when u.status = io.github"
          + ".mariusbayizere.fraudshield.auth.domain.AccountStatus.LOCKED then 1 else 0 end,"
          + " u.lockedUntil = case when u.status = io.github.mariusbayizere"
          + ".fraudshield.auth.domain.AccountStatus.LOCKED then null else u.lockedUntil end,"
          + " u.status = case when u.status = io.github.mariusbayizere.fraudshield.auth.domain"
          + ".AccountStatus.LOCKED then io.github.mariusbayizere.fraudshield.auth.domain"
          + ".AccountStatus.ACTIVE else u.status end where u.id = :id")
  int changePassword(@Param("id") UUID id, @Param("hash") String hash);

  /**
   * Increments the token version (D-27).
   *
   * @param id account
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update StaffUserEntity u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
  int bumpTokenVersion(@Param("id") UUID id);

  /**
   * Marks the email verified.
   *
   * @param id account
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update StaffUserEntity u set u.emailVerified = true where u.id = :id")
  int markEmailVerified(@Param("id") UUID id);

  /**
   * Links a Google account and stores its avatar.
   *
   * @param id account
   * @param subject Google subject
   * @param avatarUrl avatar or null
   * @return rows changed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StaffUserEntity u set u.oauthProvider = 'GOOGLE', u.oauthId = :subject,"
          + " u.avatarUrl = coalesce(:avatar, u.avatarUrl), u.emailVerified = true"
          + " where u.id = :id")
  int linkGoogle(
      @Param("id") UUID id, @Param("subject") String subject, @Param("avatar") String avatarUrl);
}
