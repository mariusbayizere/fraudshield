package io.github.mariusbayizere.fraudshield.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FR-06-06")
class AuditEventTest {

  private static final UUID INSTITUTION = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

  @Test
  void buildsAnEventWithContextAndKeepsNullValuesInBeforeAndAfter() {
    Map<String, Object> before = new HashMap<>();
    before.put("phone", null);
    AuditActor actor = new AuditActor(UUID.randomUUID(), "Amani", "Mukiza", StaffRole.ADMIN);
    AuditEvent event =
        AuditEvent.of(INSTITUTION, AuditEventType.USER_ADMIN, "USER_UPDATED")
            .entity("user", 42)
            .actor(actor)
            .before(before)
            .after(Map.of("phone", "+250780000001"))
            .context(new RequestContext("192.0.2.1", "agent", UUID.randomUUID()))
            .at(NOW);
    assertThat(event.entityId()).isEqualTo("42");
    assertThat(event.before()).containsEntry("phone", null);
    assertThat(event.actorIfAny()).contains(actor);
    assertThat(event.ipAddress()).isEqualTo("192.0.2.1");
    before.put("phone", "changed");
    assertThat(event.before()).containsEntry("phone", null);
  }

  @Test
  void rejectsActionsThatTheDatabaseWouldReject() {
    assertThatThrownBy(
            () ->
                AuditEvent.of(INSTITUTION, AuditEventType.AUTH, "login")
                    .entity("user", "x")
                    .at(NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AuditEvent.of(INSTITUTION, AuditEventType.AUTH, "LOGIN").entity("", "x").at(NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AuditEvent.of(INSTITUTION, AuditEventType.AUTH, "LOGIN")
                    .entity("user", "x".repeat(129))
                    .at(NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncatesAnOverlongUserAgentInsteadOfFailingTheAuditedChange() {
    AuditEvent event =
        AuditEvent.of(INSTITUTION, AuditEventType.AUTH, "LOGIN_SUCCEEDED")
            .entity("user", "x")
            .context(new RequestContext(null, "a".repeat(5000), null))
            .at(NOW);
    assertThat(event.userAgent()).hasSize(1024);
    assertThat(event.actorIfAny()).isEmpty();
  }

  @Test
  void thereAreExactlyTwelveEventTypes() {
    assertThat(AuditEventType.values()).hasSize(12);
  }
}
