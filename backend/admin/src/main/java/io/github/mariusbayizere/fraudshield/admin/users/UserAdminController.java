package io.github.mariusbayizere.fraudshield.admin.users;

import io.github.mariusbayizere.fraudshield.admin.support.IdempotencyStore;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import io.github.mariusbayizere.fraudshield.auth.web.StaffUserView;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staff accounts and approvals for administrators (FR-06-01, FR-06-02, D-23, D-24). Responses for a
 * single account carry an {@code ETag} with its version, which a PATCH must send back as {@code
 * version} (ADR 0027: the contract's StaffUser has no version field).
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin")
public class UserAdminController {

  private static final String ADMIN = "hasAnyRole('ADMIN')";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final UserAdministrationService users;
  private final IdempotencyStore idempotency;

  /**
   * Creates the controller.
   *
   * @param users user administration
   * @param idempotency idempotency store
   */
  public UserAdminController(UserAdministrationService users, IdempotencyStore idempotency) {
    this.users = Objects.requireNonNull(users);
    this.idempotency = Objects.requireNonNull(idempotency);
  }

  /**
   * Lists accounts.
   *
   * @param jwt caller
   * @param status status filter
   * @param role role filter
   * @param cursor cursor
   * @param limit page size
   * @return a page of accounts
   */
  @ContractOperation("listUsers")
  @PreAuthorize(ADMIN)
  @GetMapping("/users")
  public Map<String, Object> listUsers(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) AccountStatus status,
      @RequestParam(required = false) StaffRole role,
      @RequestParam(required = false) @Size(max = 512) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    UserAdministrationService.Page page =
        users.list(AccessTokens.claims(jwt), status, role, cursor, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", page.items().stream().map(StaffUserView::of).toList());
    body.put("next_cursor", page.nextCursor());
    return body;
  }

  /**
   * Creates an account (FR-06-01); replays with the same Idempotency-Key return the first result.
   *
   * @param jwt caller
   * @param key Idempotency-Key
   * @param body account
   * @param request request
   * @return 201 with the account
   */
  @ContractOperation("createUser")
  @PreAuthorize(ADMIN)
  @PostMapping("/users")
  public ResponseEntity<StaffUserView> createUser(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody UserRequests.Create body,
      HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    String response =
        idempotency.once(
            "createUser:" + claims.userId(),
            key,
            JSON.writeValueAsString(body),
            () ->
                JSON.writeValueAsString(
                    StaffUserView.of(users.create(claims, body, Requests.context(request)))));
    StaffUserView view = JSON.readValue(response, StaffUserView.class);
    return ResponseEntity.status(HttpStatus.CREATED).body(view);
  }

  /**
   * One account.
   *
   * @param jwt caller
   * @param userId account
   * @return the account with its version as ETag
   */
  @ContractOperation("getUser")
  @PreAuthorize(ADMIN)
  @GetMapping("/users/{user_id}")
  public ResponseEntity<StaffUserView> getUser(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("user_id") UUID userId) {
    return withVersion(users.get(AccessTokens.claims(jwt), userId));
  }

  /**
   * Updates profile, role or status (FR-06-02).
   *
   * @param jwt caller
   * @param userId account
   * @param body changes and the version last seen
   * @param request request
   * @return the account with its new version as ETag
   */
  @ContractOperation("updateUser")
  @PreAuthorize(ADMIN)
  @PatchMapping("/users/{user_id}")
  public ResponseEntity<StaffUserView> updateUser(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("user_id") UUID userId,
      @Valid @RequestBody UserRequests.Update body,
      HttpServletRequest request) {
    if (!body.hasChanges()) {
      throw ProblemException.validation(
          "", "required", "At least one field besides version is required");
    }
    return withVersion(
        users.update(AccessTokens.claims(jwt), userId, body, Requests.context(request)));
  }

  /**
   * Accounts awaiting approval.
   *
   * @param jwt caller
   * @return the accounts
   */
  @ContractOperation("listPendingApprovals")
  @PreAuthorize(ADMIN)
  @GetMapping("/approvals")
  public List<StaffUserView> listPendingApprovals(@AuthenticationPrincipal Jwt jwt) {
    return users.pending(AccessTokens.claims(jwt)).stream().map(StaffUserView::of).toList();
  }

  /**
   * Approves with a granted role, or rejects.
   *
   * @param jwt caller
   * @param userId account
   * @param body decision
   * @param request request
   * @return the account
   */
  @ContractOperation("decideApproval")
  @PreAuthorize(ADMIN)
  @PostMapping("/approvals/{user_id}")
  public ResponseEntity<StaffUserView> decideApproval(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("user_id") UUID userId,
      @Valid @RequestBody UserRequests.ApprovalDecision body,
      HttpServletRequest request) {
    return withVersion(
        users.decide(AccessTokens.claims(jwt), userId, body, Requests.context(request)));
  }

  private static ResponseEntity<StaffUserView> withVersion(StaffAccount account) {
    return ResponseEntity.ok()
        .eTag(Long.toString(account.version()))
        .body(StaffUserView.of(account));
  }
}
