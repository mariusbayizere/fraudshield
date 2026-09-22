package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.AccountUnlockService;
import io.github.mariusbayizere.fraudshield.auth.account.AuthenticationService;
import io.github.mariusbayizere.fraudshield.auth.account.GoogleSignInService;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordChangeService;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordResetService;
import io.github.mariusbayizere.fraudshield.auth.account.RegistrationService;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdentityClient;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleTokenVault;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.SigningKeys;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.IssuedSession;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The auth operations of the contract (FR-07, D-23, D-24, D-26, D-27). */
@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String STAFF =
      "hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER', 'ADMIN')";

  private final AuthenticationService authentication;
  private final GoogleSignInService google;
  private final GoogleIdentityClient googleClient;
  private final GoogleTokenVault googleTokens;
  private final SessionService sessions;
  private final RegistrationService registration;
  private final PasswordResetService passwordReset;
  private final PasswordChangeService passwordChange;
  private final AccountUnlockService unlock;
  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final SigningKeys signingKeys;
  private final SessionCookies cookies;

  /**
   * Creates the controller.
   *
   * @param authentication sign-in
   * @param google Google sign-in, or null when not configured
   * @param googleClient Google endpoints, or null when not configured
   * @param googleTokens Google token vault
   * @param sessions sessions
   * @param registration registration
   * @param passwordReset password reset
   * @param passwordChange password change
   * @param unlock account unlock
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param signingKeys access-token keys
   * @param cookies session cookies
   */
  public AuthController(
      AuthenticationService authentication,
      Optional<GoogleSignInService> google,
      Optional<GoogleIdentityClient> googleClient,
      GoogleTokenVault googleTokens,
      SessionService sessions,
      RegistrationService registration,
      PasswordResetService passwordReset,
      PasswordChangeService passwordChange,
      AccountUnlockService unlock,
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      SigningKeys signingKeys,
      SessionCookies cookies) {
    this.authentication = Objects.requireNonNull(authentication);
    this.google = google.orElse(null);
    this.googleClient = googleClient.orElse(null);
    this.googleTokens = Objects.requireNonNull(googleTokens);
    this.sessions = Objects.requireNonNull(sessions);
    this.registration = Objects.requireNonNull(registration);
    this.passwordReset = Objects.requireNonNull(passwordReset);
    this.passwordChange = Objects.requireNonNull(passwordChange);
    this.unlock = Objects.requireNonNull(unlock);
    this.accounts = Objects.requireNonNull(accounts);
    this.tenants = Objects.requireNonNull(tenants);
    this.signingKeys = Objects.requireNonNull(signingKeys);
    this.cookies = Objects.requireNonNull(cookies);
  }

  /**
   * Email and password sign-in.
   *
   * @param body credentials
   * @param request request
   * @return token response with session cookies
   */
  @ContractOperation("login")
  @PreAuthorize("permitAll()")
  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(
      @Valid @RequestBody AuthRequests.Login body, HttpServletRequest request) {
    return signedIn(authentication.login(body.email(), body.password(), Requests.context(request)));
  }

  /**
   * Google sign-in.
   *
   * @param body authorization code and PKCE verifier
   * @param request request
   * @return token response, or 202 for a new account awaiting approval
   */
  @ContractOperation("loginWithGoogle")
  @PreAuthorize("permitAll()")
  @PostMapping("/google")
  public ResponseEntity<Map<String, Object>> loginWithGoogle(
      @Valid @RequestBody AuthRequests.GoogleSignIn body, HttpServletRequest request) {
    if (google == null) {
      throw ProblemException.of(
              "service-unavailable",
              503,
              "Google sign-in temporarily unavailable",
              "Google sign-in is not configured; sign in with your email and password")
          .withRetryAfter(300);
    }
    GoogleSignInService.Result result =
        google.signIn(
            body.authorizationCode(),
            body.codeVerifier(),
            body.redirectUri(),
            Requests.context(request));
    if (result.pendingApproval()) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(pendingApproval());
    }
    return signedIn(result.session());
  }

  /**
   * Refresh-token rotation.
   *
   * @param refreshToken refresh cookie
   * @param request request
   * @return new token response and cookies
   */
  @ContractOperation("refreshToken")
  @PreAuthorize("permitAll()")
  @PostMapping("/refresh")
  public ResponseEntity<Map<String, Object>> refresh(
      @CookieValue(name = SessionCookies.REFRESH, required = false) String refreshToken,
      HttpServletRequest request) {
    if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 256) {
      throw ProblemException.unauthorized();
    }
    return signedIn(sessions.refresh(refreshToken, Requests.context(request)));
  }

  /**
   * Sign-out of this session; revokes the Google token when one is held (FR-07-09).
   *
   * @param jwt access token
   * @param request request
   * @return 204 with cookies cleared
   */
  @ContractOperation("logout")
  @PreAuthorize(STAFF)
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    sessions.logout(claims, Requests.context(request));
    if (googleClient != null) {
      googleTokens.take(claims.sessionId()).ifPresent(googleClient::revoke);
    }
    return noContentClearingCookies();
  }

  /**
   * Sign-out everywhere (D-27).
   *
   * @param jwt access token
   * @param request request
   * @return 204 with cookies cleared
   */
  @ContractOperation("logoutEverywhere")
  @PreAuthorize(STAFF)
  @PostMapping("/logout-all")
  public ResponseEntity<Void> logoutEverywhere(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    sessions.logoutEverywhere(AccessTokens.claims(jwt), Requests.context(request));
    return noContentClearingCookies();
  }

  /**
   * The signed-in user (FR-04-01).
   *
   * @param jwt access token
   * @return the user
   */
  @ContractOperation("getCurrentUser")
  @PreAuthorize(STAFF)
  @GetMapping("/me")
  public StaffUserView me(@AuthenticationPrincipal Jwt jwt) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants.inTenant(
        claims.institutionId(),
        () ->
            accounts
                .findById(claims.userId())
                .map(StaffUserView::of)
                .orElseThrow(ProblemException::unauthorized));
  }

  /**
   * Self-registration (D-24).
   *
   * @param body registration
   * @param request request
   * @return 202 in every case
   */
  @ContractOperation("register")
  @PreAuthorize("permitAll()")
  @PostMapping("/register")
  public ResponseEntity<Map<String, Object>> register(
      @Valid @RequestBody AuthRequests.Registration body, HttpServletRequest request) {
    registration.register(
        new RegistrationService.Registration(
            body.firstName(),
            body.lastName(),
            body.email(),
            body.phone(),
            body.employeeId(),
            Department.valueOf(body.department()),
            StaffRole.valueOf(body.requestedRole()),
            body.password(),
            StaffLocale.valueOf(body.preferredLocale())),
        Requests.context(request));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(pendingApproval());
  }

  /**
   * Availability of an email or employee ID (E.8).
   *
   * @param field field
   * @param value value
   * @param request request
   * @return availability
   */
  @ContractOperation("checkAvailability")
  @PreAuthorize("permitAll()")
  @GetMapping("/availability")
  public Map<String, Boolean> availability(
      @RequestParam RegistrationService.AvailabilityField field,
      @RequestParam @NotNull @Size(min = 1, max = 254) String value,
      HttpServletRequest request) {
    return Map.of("available", registration.available(field, value, Requests.context(request)));
  }

  /**
   * Email verification.
   *
   * @param body token
   * @param request request
   * @return 204
   */
  @ContractOperation("verifyEmail")
  @PreAuthorize("permitAll()")
  @PostMapping("/email-verification")
  public ResponseEntity<Void> verifyEmail(
      @Valid @RequestBody AuthRequests.SingleUseToken body, HttpServletRequest request) {
    registration.verifyEmail(body.token(), Requests.context(request));
    return ResponseEntity.noContent().build();
  }

  /**
   * Reset-code request (FR-07-08).
   *
   * @param body email
   * @param request request
   * @return 202 in every case
   */
  @ContractOperation("requestPasswordReset")
  @PreAuthorize("permitAll()")
  @PostMapping("/password-reset/request")
  public ResponseEntity<Void> requestPasswordReset(
      @Valid @RequestBody AuthRequests.ResetRequest body, HttpServletRequest request) {
    passwordReset.request(body.email(), Requests.context(request));
    return ResponseEntity.accepted().build();
  }

  /**
   * Reset-code verification.
   *
   * @param body email and code
   * @param request request
   * @return reset token
   */
  @ContractOperation("verifyPasswordResetCode")
  @PreAuthorize("permitAll()")
  @PostMapping("/password-reset/verify")
  public ResponseEntity<Map<String, Object>> verifyPasswordResetCode(
      @Valid @RequestBody AuthRequests.ResetVerify body, HttpServletRequest request) {
    PasswordResetService.ResetToken token =
        passwordReset.verify(body.email(), body.code(), Requests.context(request));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("reset_token", token.resetToken());
    response.put("expires_at", token.expiresAt().toString());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  /**
   * Reset completion; ends every session (FR-07-09).
   *
   * @param body reset token and new password
   * @param request request
   * @return 204
   */
  @ContractOperation("completePasswordReset")
  @PreAuthorize("permitAll()")
  @PostMapping("/password-reset/complete")
  public ResponseEntity<Void> completePasswordReset(
      @Valid @RequestBody AuthRequests.ResetComplete body, HttpServletRequest request) {
    passwordReset.complete(body.resetToken(), body.newPassword(), Requests.context(request));
    return ResponseEntity.noContent().build();
  }

  /**
   * Password change; ends every session (FR-07-09).
   *
   * @param jwt access token
   * @param body current and new password
   * @param request request
   * @return 204 with cookies cleared
   */
  @ContractOperation("changePassword")
  @PreAuthorize(STAFF)
  @PutMapping("/password")
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody AuthRequests.PasswordChange body,
      HttpServletRequest request) {
    passwordChange.change(
        AccessTokens.claims(jwt),
        body.currentPassword(),
        body.newPassword(),
        Requests.context(request));
    return noContentClearingCookies();
  }

  /**
   * Account unlock by emailed link (FR-07-06).
   *
   * @param body token
   * @param request request
   * @return 204
   */
  @ContractOperation("unlockAccount")
  @PreAuthorize("permitAll()")
  @PostMapping("/unlock")
  public ResponseEntity<Void> unlockAccount(
      @Valid @RequestBody AuthRequests.SingleUseToken body, HttpServletRequest request) {
    unlock.unlock(body.token(), Requests.context(request));
    return ResponseEntity.noContent().build();
  }

  /**
   * Public keys for access-token verification.
   *
   * @return the JWKS
   */
  @ContractOperation("getJwks")
  @PreAuthorize("permitAll()")
  @GetMapping("/jwks.json")
  public ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
        .body(signingKeys.publicJwks());
  }

  private ResponseEntity<Map<String, Object>> signedIn(IssuedSession session) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", session.accessToken());
    body.put("token_type", "Bearer");
    body.put("expires_in", session.expiresInSeconds());
    body.put("user", StaffUserView.of(session.account()));
    HttpHeaders headers = new HttpHeaders();
    cookies.issue(session).forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));
    headers.setCacheControl(CacheControl.noStore());
    return new ResponseEntity<>(body, headers, HttpStatus.OK);
  }

  private ResponseEntity<Void> noContentClearingCookies() {
    HttpHeaders headers = new HttpHeaders();
    cookies.clear().forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));
    return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
  }

  private static Map<String, Object> pendingApproval() {
    return Map.of("status", "PENDING_APPROVAL", "message_key", "auth.pending_approval");
  }
}
