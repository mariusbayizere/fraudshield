package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Request bodies of the auth operations, validated by Bean Validation with the contract's rules.
 * Unknown fields are refused by the API-wide mapper setting (400 {@code unknown_field}, contract
 * {@code additionalProperties: false}; see AuthAutoConfiguration#strictJsonFields).
 */
public final class AuthRequests {

  static final int MAX_EMAIL = 254;
  static final int TRANSPORT_PASSWORD_CAP = 1024;

  private AuthRequests() {}

  /**
   * LoginRequest.
   *
   * @param email email
   * @param password password, up to the 1,024-character transport cap
   */
  public record Login(
      @NotNull @Email @Size(max = MAX_EMAIL) String email,
      @NotNull @Size(min = 1, max = TRANSPORT_PASSWORD_CAP) String password) {

    @Override
    public String toString() {
      return "Login[<redacted>]";
    }
  }

  /**
   * GoogleSignInRequest.
   *
   * @param authorizationCode authorization code
   * @param codeVerifier PKCE verifier
   * @param redirectUri redirect URI
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GoogleSignIn(
      @NotNull @Size(min = 10, max = 2048) String authorizationCode,
      @NotNull @Pattern(regexp = "^[A-Za-z0-9._~-]{43,128}$") String codeVerifier,
      @NotNull @Size(max = 2048) String redirectUri) {

    @Override
    public String toString() {
      return "GoogleSignIn[<redacted>]";
    }
  }

  /**
   * RegistrationRequest (D-24: department and requested role are separate).
   *
   * @param firstName first name
   * @param lastName last name
   * @param email email
   * @param phone E.164 phone
   * @param employeeId employee ID
   * @param department department
   * @param requestedRole requested role
   * @param password password
   * @param preferredLocale locale
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Registration(
      @NotNull @ValidPersonName String firstName,
      @NotNull @ValidPersonName String lastName,
      @NotNull @Email @Size(max = MAX_EMAIL) String email,
      @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
      @NotNull @Pattern(regexp = "^[A-Za-z0-9]{4,20}$") String employeeId,
      @NotNull @EnumValue(of = Department.class) String department,
      @NotNull @EnumValue(of = StaffRole.class) String requestedRole,
      @NotNull @ValidPassword String password,
      @NotNull @EnumValue(of = StaffLocale.class) String preferredLocale) {

    @Override
    public String toString() {
      return "Registration[<redacted>]";
    }
  }

  /**
   * SingleUseTokenRequest.
   *
   * @param token token
   */
  public record SingleUseToken(@NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{22,128}$") String token) {

    @Override
    public String toString() {
      return "SingleUseToken[<redacted>]";
    }
  }

  /**
   * Reset-code request.
   *
   * @param email email
   */
  public record ResetRequest(@NotNull @Email @Size(max = MAX_EMAIL) String email) {}

  /**
   * Reset-code verification.
   *
   * @param email email
   * @param code 6-digit code
   */
  public record ResetVerify(
      @NotNull @Email @Size(max = MAX_EMAIL) String email,
      @NotNull @Pattern(regexp = "^[0-9]{6}$") String code) {

    @Override
    public String toString() {
      return "ResetVerify[<redacted>]";
    }
  }

  /**
   * Reset completion.
   *
   * @param resetToken reset token
   * @param newPassword new password
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ResetComplete(
      @NotNull @Size(min = 32) String resetToken, @NotNull @ValidPassword String newPassword) {

    @Override
    public String toString() {
      return "ResetComplete[<redacted>]";
    }
  }

  /**
   * Password change.
   *
   * @param currentPassword current password, up to the transport cap
   * @param newPassword new password
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PasswordChange(
      @NotNull @Size(min = 1, max = TRANSPORT_PASSWORD_CAP) String currentPassword,
      @NotNull @ValidPassword String newPassword) {

    @Override
    public String toString() {
      return "PasswordChange[<redacted>]";
    }
  }
}
