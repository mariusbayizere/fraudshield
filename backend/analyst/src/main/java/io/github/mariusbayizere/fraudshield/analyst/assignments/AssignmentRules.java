package io.github.mariusbayizere.fraudshield.analyst.assignments;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;

/**
 * ADR 0116's rank rules, without the database. Ranks are ANALYST &lt; SENIOR_ANALYST &lt;
 * RISK_OFFICER (E.8, {@link Views#rank}); ADMIN takes no part in alerts. "On duty" is read as
 * status ACTIVE or LOCKED, the statuses that can hold a session (ASSUMED, ADR 0116 point 3).
 */
final class AssignmentRules {

  private AssignmentRules() {}

  /**
   * Whether a user with this status counts as on duty (ASSUMED: ACTIVE or LOCKED).
   *
   * @param status the user's status
   * @return true for ACTIVE or LOCKED
   */
  static boolean onDuty(String status) {
    return "ACTIVE".equals(status) || "LOCKED".equals(status);
  }

  /**
   * Whether the role decides alerts.
   *
   * @param role the role
   * @return true for ANALYST, SENIOR_ANALYST and RISK_OFFICER
   */
  static boolean decisionRole(StaffRole role) {
    return role != null && role != StaffRole.ADMIN;
  }

  /**
   * A1: an escalated entry can be assigned only by a caller at or above its target role.
   *
   * @param caller the caller's role
   * @param target the entry's escalation target, or null for an entry that is not escalated
   * @return whether the caller may act on the entry
   */
  static boolean reaches(StaffRole caller, StaffRole target) {
    return target == null || Views.rank(caller) >= Views.rank(target);
  }

  /**
   * A3: an entry held by an on-duty user whose current role ranks above the caller cannot be
   * reassigned or unassigned by the caller. A holder who is no longer on duty holds nothing.
   *
   * @param caller the caller's role
   * @param holderRole the current assignee's current role
   * @param holderStatus the current assignee's current status
   * @return whether the assignment is held against the caller
   */
  static boolean heldAgainst(StaffRole caller, StaffRole holderRole, String holderStatus) {
    return onDuty(holderStatus) && Views.rank(holderRole) > Views.rank(caller);
  }

  /**
   * A2: the assignee is an on-duty user with a decision role, ranking at or below the caller and,
   * for an escalated entry, at or above its target role.
   *
   * @param caller the caller's role
   * @param target the entry's escalation target, or null
   * @param assigneeRole the assignee's role
   * @param assigneeStatus the assignee's status
   * @return whether the assignee is eligible
   */
  static boolean eligible(
      StaffRole caller, StaffRole target, StaffRole assigneeRole, String assigneeStatus) {
    return onDuty(assigneeStatus)
        && decisionRole(assigneeRole)
        && Views.rank(assigneeRole) <= Views.rank(caller)
        && (target == null || Views.rank(assigneeRole) >= Views.rank(target));
  }

  /**
   * The lowest rank the assign mode of {@code listAssignableStaff} lists: the target's for an
   * escalated entry, else ANALYST's.
   *
   * @param target the entry's escalation target, or null
   * @return the lowest rank
   */
  static int lowestRank(StaffRole target) {
    return target == null ? Views.rank(StaffRole.ANALYST) : Views.rank(target);
  }
}
