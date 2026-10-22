package io.github.mariusbayizere.fraudshield.analyst.decisions;

import io.github.mariusbayizere.fraudshield.auth.web.EnumValue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Request bodies of the four decision operations (the contract's schemas, P2). */
public final class DecisionRequests {

  private DecisionRequests() {}

  /** An analyst decision's kind. */
  public enum Kind {
    /** Fraud. */
    CONFIRM_FRAUD,
    /** Legitimate. */
    MARK_LEGITIMATE
  }

  /** An escalation's target. */
  public enum Target {
    /** A senior analyst. */
    SENIOR_ANALYST,
    /** A risk officer. */
    RISK_OFFICER
  }

  /**
   * AlertDecisionRequest.
   *
   * @param decision CONFIRM_FRAUD or MARK_LEGITIMATE
   * @param comment the analyst's comment, 10 to 2,000 characters
   * @param alertVersion the version the analyst saw
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Decide(
      @NotNull @EnumValue(of = Kind.class) String decision,
      @NotNull @Size(min = 10, max = 2000) String comment,
      @NotNull @Min(0) Long alertVersion) {}

  /**
   * EscalationRequest.
   *
   * @param targetRole SENIOR_ANALYST or RISK_OFFICER
   * @param reason 10 to 2,000 characters
   * @param alertVersion the version the caller saw
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Escalate(
      @NotNull @EnumValue(of = Target.class) String targetRole,
      @NotNull @Size(min = 10, max = 2000) String reason,
      @NotNull @Min(0) Long alertVersion) {}

  /**
   * OverrideRequest.
   *
   * @param originalDecisionId the decision overridden
   * @param newDecision CONFIRM_FRAUD or MARK_LEGITIMATE
   * @param reason 20 to 2,000 characters
   * @param alertVersion the version the caller saw
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Override(
      @NotNull UUID originalDecisionId,
      @NotNull @EnumValue(of = Kind.class) String newDecision,
      @NotNull @Size(min = 20, max = 2000) String reason,
      @NotNull @Min(0) Long alertVersion) {}
}
