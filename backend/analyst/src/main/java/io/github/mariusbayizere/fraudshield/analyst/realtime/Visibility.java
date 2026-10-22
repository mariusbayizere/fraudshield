package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.UUID;

/**
 * Who may receive an event about an entry (ADR 0115 point 4): the rule {@code listAlerts} applies
 * in SQL, evaluated in memory; a test proves the two equal.
 *
 * @param escalatedFrom the entry's {@code escalated_from_alert_id}, or null
 * @param targetRole its {@code escalation_target_role}, or null
 * @param escalatedBy the user who escalated it, or null
 */
record Visibility(UUID escalatedFrom, StaffRole targetRole, UUID escalatedBy) {

  /** An entry that is not an escalation: every connected role. */
  static final Visibility EVERYONE = new Visibility(null, null, null);

  /**
   * Whether a session would be shown the entry by {@code listAlerts}.
   *
   * @param binding the session
   * @return true to deliver
   */
  boolean admits(Binding binding) {
    if (escalatedFrom == null) {
      return true;
    }
    if (binding.userId().equals(escalatedBy)) {
      return true;
    }
    return targetRole != null && Views.rank(binding.role()) >= Views.rank(targetRole);
  }
}
