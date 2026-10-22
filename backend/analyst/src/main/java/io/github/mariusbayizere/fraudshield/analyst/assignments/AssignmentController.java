package io.github.mariusbayizere.fraudshield.analyst.assignments;

import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alert assignment (ADR 0116, UX-ROLE-SN-02, UX-ROLE-SN-01): assign, self-assign or unassign an
 * alert, and the staff the caller may assign to or filter by. Assignment is advisory.
 */
@RestController
@RequestMapping("/api/v1")
public class AssignmentController {

  private static final Map<String, AlertAssignments.Purpose> PURPOSES =
      Map.of("assign", AlertAssignments.Purpose.ASSIGN, "filter", AlertAssignments.Purpose.FILTER);

  private final AlertAssignments assignments;

  /**
   * Creates the controller.
   *
   * @param assignments the assignment operations
   */
  public AssignmentController(AlertAssignments assignments) {
    this.assignments = Objects.requireNonNull(assignments, "assignments");
  }

  /**
   * Assigns an alert, takes it from the pool, or returns it to the pool: 200 with the entry.
   *
   * @param jwt caller
   * @param alertId the alert
   * @param key Idempotency-Key
   * @param body the assignment
   * @param request request
   * @return the entry's summary as it now stands
   */
  @ContractOperation("assignAlert")
  @PreAuthorize("hasAnyRole('SENIOR_ANALYST', 'RISK_OFFICER')")
  @PutMapping("/alerts/{alert_id}/assignment")
  public Map<String, Object> assign(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("alert_id") UUID alertId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody AssignmentRequest body,
      HttpServletRequest request) {
    UUID assignee = body.assignee();
    return assignments.assign(caller(jwt, request), alertId, key, assignee, body.alertVersion());
  }

  /**
   * The staff the caller may assign alerts to, or may filter alerts by. Never e-mail.
   *
   * @param jwt caller
   * @param alertId an alert, with purpose=assign only
   * @param purpose assign or filter
   * @param request request
   * @return the list
   */
  @ContractOperation("listAssignableStaff")
  @PreAuthorize("hasAnyRole('SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/team/assignees")
  public Map<String, Object> assignees(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "alert_id", required = false) UUID alertId,
      @RequestParam(defaultValue = "assign") String purpose,
      HttpServletRequest request) {
    AlertAssignments.Purpose mode = PURPOSES.get(purpose);
    if (mode == null) {
      throw ProblemException.validation("purpose", "unsupported_value", "Unsupported value");
    }
    return assignments.staff(caller(jwt, request), mode, alertId);
  }

  private static AlertAssignments.Caller caller(Jwt jwt, HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return new AlertAssignments.Caller(
        claims.userId(), claims.institutionId(), claims.role(), Requests.context(request));
  }
}
