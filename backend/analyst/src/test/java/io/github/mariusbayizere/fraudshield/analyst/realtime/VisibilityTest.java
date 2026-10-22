package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Point 4's filter, case by case. Its equality with listAlerts, through the change publisher's
 * re-read and the fan-out's mapping, is
 * AnalystRealtimeTest.pushedEqualsListedForEveryEntryRoleAndUser.
 */
class VisibilityTest {

  private static Binding as(UUID user, StaffRole role) {
    return new Binding(
        new StaffClaims(user, UUID.randomUUID(), role, null, null, 0, UUID.randomUUID()),
        Instant.now().plusSeconds(60));
  }

  @Test
  @Tag("FR-04-10")
  void escalatedEntriesReachTheTargetRoleAndAboveAndTheEscalator() {
    UUID escalator = UUID.randomUUID();
    Visibility toSenior = new Visibility(UUID.randomUUID(), StaffRole.SENIOR_ANALYST, escalator);
    assertThat(toSenior.admits(as(UUID.randomUUID(), StaffRole.ANALYST))).isFalse();
    assertThat(toSenior.admits(as(escalator, StaffRole.ANALYST))).isTrue();
    assertThat(toSenior.admits(as(UUID.randomUUID(), StaffRole.SENIOR_ANALYST))).isTrue();
    assertThat(toSenior.admits(as(UUID.randomUUID(), StaffRole.RISK_OFFICER))).isTrue();
    Visibility toOfficer = new Visibility(UUID.randomUUID(), StaffRole.RISK_OFFICER, escalator);
    assertThat(toOfficer.admits(as(UUID.randomUUID(), StaffRole.SENIOR_ANALYST))).isFalse();
    assertThat(Visibility.EVERYONE.admits(as(UUID.randomUUID(), StaffRole.ANALYST))).isTrue();
  }
}
