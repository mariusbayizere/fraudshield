package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A staff user (V2 {@code users}) as the demo seeder writes one (ADR 0068 point 4).
 *
 * <p><b>At merge with M7:</b> the staff module owns this table and maps it as {@code
 * StaffUserEntity}, with the optimistic-lock {@code version} column and the whole account
 * lifecycle. This entity exists only because the seeder must write users and M6's branch has no
 * staff module; whoever merges should delete it and point the seeder at {@code StaffUserEntity},
 * which is recorded in {@code docs/parallel/M6_updates.md}.
 */
@Entity
@DynamicUpdate
@Table(name = "users")
public class DemoUserEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "institution_id", nullable = false, updatable = false)
  private UUID institutionId;

  @Column(name = "first_name", nullable = false)
  private String firstName;

  @Column(name = "last_name", nullable = false)
  private String lastName;

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "role", nullable = false)
  private String role;

  @Column(name = "requested_role", nullable = false)
  private String requestedRole;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "preferred_locale", nullable = false)
  private String preferredLocale;

  @Column(name = "employee_id", nullable = false)
  private String employeeId;

  @Column(name = "department", nullable = false)
  private String department;

  /** For Hibernate. */
  protected DemoUserEntity() {}

  /**
   * An active demo account.
   *
   * @param id the user
   * @param institutionId institution
   * @param firstName given name
   * @param lastName family name
   * @param email address
   * @param passwordHash BCrypt hash
   * @param role the role, also the requested role
   * @param employeeId employee number
   * @param department department
   */
  public DemoUserEntity(
      UUID id,
      UUID institutionId,
      String firstName,
      String lastName,
      String email,
      String passwordHash,
      String role,
      String employeeId,
      String department) {
    this.id = id;
    this.institutionId = institutionId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.requestedRole = role;
    this.status = "ACTIVE";
    this.emailVerified = true;
    this.preferredLocale = "en";
    this.employeeId = employeeId;
    this.department = department;
  }

  /**
   * The user's id.
   *
   * @return the id
   */
  public UUID id() {
    return id;
  }
}
