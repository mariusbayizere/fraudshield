package io.github.mariusbayizere.fraudshield.admin.users;

import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.web.EnumValue;
import io.github.mariusbayizere.fraudshield.auth.web.ValidPersonName;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Request bodies of the user administration operations. */
public final class UserRequests {

  private UserRequests() {}

  /**
   * StaffUserCreate (FR-06-01).
   *
   * @param firstName first name
   * @param lastName last name
   * @param email email
   * @param phone E.164 phone, optional
   * @param employeeId employee ID
   * @param department department
   * @param role role
   * @param preferredLocale locale
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Create(
      @NotNull @ValidPersonName String firstName,
      @NotNull @ValidPersonName String lastName,
      @NotNull @Email @Size(max = 254) String email,
      @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
      @NotNull @Pattern(regexp = "^[A-Za-z0-9]{4,20}$") String employeeId,
      @NotNull @EnumValue(of = Department.class) String department,
      @NotNull @EnumValue(of = StaffRole.class) String role,
      @NotNull @EnumValue(of = StaffLocale.class) String preferredLocale) {}

  /** Statuses an administrator may set; PENDING_APPROVAL is left through the approvals endpoint. */
  public enum SettableStatus {
    /** Active. */
    ACTIVE,
    /** Deactivated. */
    DEACTIVATED,
    /** Locked by an administrator. */
    LOCKED
  }

  /**
   * StaffUserUpdate (FR-06-02). Absent fields are unchanged.
   *
   * @param version the version the administrator last saw
   * @param firstName first name
   * @param lastName last name
   * @param phone phone
   * @param department department
   * @param role role
   * @param status status
   * @param preferredLocale locale
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Update(
      @NotNull @Min(0) Long version,
      @ValidPersonName String firstName,
      @ValidPersonName String lastName,
      @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
      @EnumValue(of = Department.class) String department,
      @EnumValue(of = StaffRole.class) String role,
      @EnumValue(of = SettableStatus.class) String status,
      @EnumValue(of = StaffLocale.class) String preferredLocale) {

    /**
     * Whether any field besides the version is present (contract minProperties 2).
     *
     * @return whether the update changes anything
     */
    public boolean hasChanges() {
      return firstName != null
          || lastName != null
          || phone != null
          || department != null
          || role != null
          || status != null
          || preferredLocale != null;
    }
  }

  /** An approval decision. */
  public enum Decision {
    /** Approve with a granted role. */
    APPROVE,
    /** Reject. */
    REJECT
  }

  /**
   * Approval decision (D-23, D-24).
   *
   * @param decision APPROVE or REJECT
   * @param grantedRole role, required to approve
   * @param reason optional reason
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApprovalDecision(
      @NotNull @EnumValue(of = Decision.class) String decision,
      @EnumValue(of = StaffRole.class) String grantedRole,
      @Size(max = 500) String reason) {}
}
