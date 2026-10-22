package io.github.mariusbayizere.fraudshield.analyst.assignments;

import static io.github.mariusbayizere.fraudshield.common.config.StaffRole.ADMIN;
import static io.github.mariusbayizere.fraudshield.common.config.StaffRole.ANALYST;
import static io.github.mariusbayizere.fraudshield.common.config.StaffRole.RISK_OFFICER;
import static io.github.mariusbayizere.fraudshield.common.config.StaffRole.SENIOR_ANALYST;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR 0116's rank rules A1 to A3, and "on duty" as ACTIVE or LOCKED. */
@Tag("FR-04-10")
class AssignmentRulesTest {

  @ParameterizedTest
  @CsvSource({"ACTIVE, true", "LOCKED, true", "DEACTIVATED, false", "PENDING_APPROVAL, false"})
  void onDutyIsActiveOrLocked(String status, boolean expected) {
    assertThat(AssignmentRules.onDuty(status)).isEqualTo(expected);
  }

  @Test
  void escalatedEntryNeedsCallerAtOrAboveItsTarget() {
    assertThat(AssignmentRules.reaches(SENIOR_ANALYST, null)).isTrue();
    assertThat(AssignmentRules.reaches(SENIOR_ANALYST, SENIOR_ANALYST)).isTrue();
    assertThat(AssignmentRules.reaches(SENIOR_ANALYST, RISK_OFFICER)).isFalse();
    assertThat(AssignmentRules.reaches(RISK_OFFICER, RISK_OFFICER)).isTrue();
  }

  @Test
  void assigneeRanksAtOrBelowTheCallerAndIsOnDuty() {
    assertThat(AssignmentRules.eligible(SENIOR_ANALYST, null, ANALYST, "ACTIVE")).isTrue();
    assertThat(AssignmentRules.eligible(SENIOR_ANALYST, null, SENIOR_ANALYST, "LOCKED")).isTrue();
    assertThat(AssignmentRules.eligible(SENIOR_ANALYST, null, RISK_OFFICER, "ACTIVE"))
        .as("no assigning up: escalation is for that")
        .isFalse();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, null, RISK_OFFICER, "ACTIVE")).isTrue();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, null, ANALYST, "DEACTIVATED")).isFalse();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, null, ANALYST, "PENDING_APPROVAL")).isFalse();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, null, ADMIN, "ACTIVE"))
        .as("ADMIN never works alerts")
        .isFalse();
  }

  @Test
  void escalatedEntryGoesOnlyToItsTargetRoleOrAbove() {
    assertThat(AssignmentRules.eligible(RISK_OFFICER, SENIOR_ANALYST, ANALYST, "ACTIVE")).isFalse();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, SENIOR_ANALYST, SENIOR_ANALYST, "ACTIVE"))
        .isTrue();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, RISK_OFFICER, SENIOR_ANALYST, "ACTIVE"))
        .isFalse();
    assertThat(AssignmentRules.eligible(RISK_OFFICER, RISK_OFFICER, RISK_OFFICER, "ACTIVE"))
        .isTrue();
  }

  @Test
  void onlyOnDutyHolderOfHigherCurrentRoleHoldsTheEntry() {
    assertThat(AssignmentRules.heldAgainst(SENIOR_ANALYST, RISK_OFFICER, "ACTIVE")).isTrue();
    assertThat(AssignmentRules.heldAgainst(SENIOR_ANALYST, RISK_OFFICER, "LOCKED"))
        .as("a failure lock caused by an outsider hands no senior a lever")
        .isTrue();
    assertThat(AssignmentRules.heldAgainst(SENIOR_ANALYST, RISK_OFFICER, "DEACTIVATED"))
        .as("a deactivated holder holds nothing")
        .isFalse();
    assertThat(AssignmentRules.heldAgainst(SENIOR_ANALYST, SENIOR_ANALYST, "ACTIVE")).isFalse();
    assertThat(AssignmentRules.heldAgainst(RISK_OFFICER, RISK_OFFICER, "ACTIVE")).isFalse();
    assertThat(AssignmentRules.heldAgainst(SENIOR_ANALYST, ADMIN, "ACTIVE"))
        .as("an assignee made ADMIN ranks below every decision role")
        .isFalse();
  }

  @Test
  void assignModeListsFromTheTargetRoleUpwards() {
    assertThat(AssignmentRules.lowestRank(null)).isEqualTo(1);
    assertThat(AssignmentRules.lowestRank(SENIOR_ANALYST)).isEqualTo(2);
    assertThat(AssignmentRules.lowestRank(RISK_OFFICER)).isEqualTo(3);
  }
}
