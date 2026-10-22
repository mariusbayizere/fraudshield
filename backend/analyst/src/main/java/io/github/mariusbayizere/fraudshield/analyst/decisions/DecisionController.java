package io.github.mariusbayizere.fraudshield.analyst.decisions;

import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analyst's decisions (FR-04-04, FR-04-10, D-29, D-44): decide, undo, escalate and override,
 * under the decision-owner contract (§9).
 */
@RestController
@RequestMapping("/api/v1/alerts/{alert_id}")
public class DecisionController {

  private final AlertDecisions decisions;

  /**
   * Creates the controller.
   *
   * @param decisions the decision operations
   */
  public DecisionController(AlertDecisions decisions) {
    this.decisions = Objects.requireNonNull(decisions, "decisions");
  }

  /**
   * Decides an alert (D1): 202, undoable for 5 seconds.
   *
   * @param jwt caller
   * @param alertId the alert
   * @param key Idempotency-Key
   * @param body the decision
   * @param request request
   * @return 202 with the decision and its undo window
   */
  @ContractOperation("decideAlert")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @PostMapping("/decisions")
  public ResponseEntity<Map<String, Object>> decide(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("alert_id") UUID alertId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody DecisionRequests.Decide body,
      HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            decisions.decide(
                caller(jwt, request),
                alertId,
                key,
                "CONFIRM_FRAUD".equals(body.decision()),
                body.comment(),
                body.alertVersion()));
  }

  /**
   * Undoes the caller's decision inside its window (D2): 204.
   *
   * @param jwt caller
   * @param alertId the alert
   * @param decisionId the decision
   * @param request request
   * @return 204
   */
  @ContractOperation("undoAlertDecision")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @PostMapping("/decisions/{decision_id}/undo")
  public ResponseEntity<Void> undo(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("alert_id") UUID alertId,
      @PathVariable("decision_id") UUID decisionId,
      HttpServletRequest request) {
    decisions.undo(caller(jwt, request), alertId, decisionId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Escalates an alert to a higher role (D6): 201 with the escalated entry.
   *
   * @param jwt caller
   * @param alertId the alert
   * @param key Idempotency-Key
   * @param body the escalation
   * @param request request
   * @return 201 with the escalated entry's summary
   */
  @ContractOperation("escalateAlert")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST')")
  @PostMapping("/escalations")
  public ResponseEntity<Map<String, Object>> escalate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("alert_id") UUID alertId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody DecisionRequests.Escalate body,
      HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            decisions.escalate(
                caller(jwt, request),
                alertId,
                key,
                StaffRole.valueOf(body.targetRole()),
                body.reason(),
                body.alertVersion()));
  }

  /**
   * Overrides a committed decision (D5): 201 with the override.
   *
   * @param jwt caller
   * @param alertId the alert
   * @param key Idempotency-Key
   * @param body the override
   * @param request request
   * @return 201 with the override's record
   */
  @ContractOperation("overrideAlertDecision")
  @PreAuthorize("hasAnyRole('RISK_OFFICER')")
  @PostMapping("/overrides")
  public ResponseEntity<Map<String, Object>> override(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("alert_id") UUID alertId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody DecisionRequests.Override body,
      HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            decisions.override(
                caller(jwt, request),
                alertId,
                key,
                body.originalDecisionId(),
                "CONFIRM_FRAUD".equals(body.newDecision()),
                body.reason(),
                body.alertVersion()));
  }

  private static AlertDecisions.Caller caller(Jwt jwt, HttpServletRequest request) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return new AlertDecisions.Caller(
        claims.userId(), claims.institutionId(), claims.role(), Requests.context(request));
  }
}
