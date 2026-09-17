package io.github.mariusbayizere.fraudshield.common.config;

import java.util.Objects;

/** A dual-control rule refused an action; the refusal maps to an HTTP problem (ADR 0014). */
public final class DualControlException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the action was refused, with the HTTP status and problem type it maps to. */
  public enum Refusal {
    /** Only a RISK_OFFICER may propose, approve or reject. */
    ROLE_NOT_PERMITTED(403, "urn:fraudshield:problem:forbidden"),
    /** The proposer cannot approve, confirm or reject their own change. */
    SELF_REVIEW(403, "urn:fraudshield:problem:self-review"),
    /** The change is no longer waiting for review. */
    CHANGE_NOT_OPEN(409, "urn:fraudshield:problem:change-not-open"),
    /** Another change of the same kind is still waiting for review. */
    OPEN_CHANGE_EXISTS(409, "urn:fraudshield:problem:open-change-exists"),
    /** The proposal was made against an older configuration version. */
    STALE_BASE_VERSION(409, "urn:fraudshield:problem:conflict"),
    /** The proposed settings equal the settings in effect. */
    NO_CHANGE(422, "urn:fraudshield:problem:validation");

    private final int status;
    private final String problemType;

    Refusal(int status, String problemType) {
      this.status = status;
      this.problemType = problemType;
    }

    /**
     * HTTP status for the refusal.
     *
     * @return the status code
     */
    public int status() {
      return status;
    }

    /**
     * RFC 9457 problem type for the refusal.
     *
     * @return the problem type URN
     */
    public String problemType() {
      return problemType;
    }
  }

  private final Refusal refusal;

  /**
   * Creates the exception.
   *
   * @param refusal the rule that refused the action
   * @param message detail for logs and the problem detail
   */
  public DualControlException(Refusal refusal, String message) {
    super(message);
    this.refusal = Objects.requireNonNull(refusal, "refusal");
  }

  /**
   * The rule that refused the action.
   *
   * @return the refusal
   */
  public Refusal refusal() {
    return refusal;
  }
}
