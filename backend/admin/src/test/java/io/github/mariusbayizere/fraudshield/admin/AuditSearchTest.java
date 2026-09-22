package io.github.mariusbayizere.fraudshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Audit search by user, date range and type over the append-only log (FR-06-06, D-32). */
@Tag("FR-06-06")
class AuditSearchTest extends AuthIntegrationTest {

  @Autowired private AuditLog audit;

  @Autowired private TenantTransactions tenants;

  @Test
  @Tag("D-32")
  void allTwelveEventTypesAreWrittenAndSearchableByTypeUserAndDateRange() {
    UUID institution = DB.createInstitution("audit-" + System.nanoTime() % 100000);
    Account admin = createAccount(institution, "ADMIN", "ACTIVE");
    Account analyst = createAccount(institution, "ANALYST", "ACTIVE");
    AuditActor actor = new AuditActor(analyst.id(), "Amani", "Uwase", StaffRole.ANALYST);
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    AuditEventType[] types = AuditEventType.values();
    for (int i = 0; i < types.length; i++) {
      AuditEvent event =
          AuditEvent.of(institution, types[i], "SAMPLE_" + types[i].name())
              .entity("sample", "entity-" + i)
              .actor(actor)
              .before(Map.of("value", i))
              .after(Map.of("value", i + 1))
              .at(base.plusSeconds(3600L * i));
      tenants.runInTenant(institution, () -> audit.record(event));
    }
    Session session = issueSession(admin);
    for (AuditEventType type : types) {
      Http.Response byType =
          http.get("/api/v1/admin/audit-events?event_type=" + type.name(), session.bearer());
      assertThat(byType.status()).isEqualTo(200);
      var item = byType.json().get("items").get(0);
      assertThat(item.get("event_type").asString()).isEqualTo(type.name());
      assertThat(item.get("actor").get("last_name").asString()).isEqualTo("Uwase");
      assertThat(item.get("row_hash").asString()).matches("^[0-9a-f]{64}$");
      assertThat(item.get("after_value").get("value").asInt())
          .isEqualTo(item.get("before_value").get("value").asInt() + 1);
    }
    Http.Response byUser =
        http.get("/api/v1/admin/audit-events?limit=200&user_id=" + analyst.id(), session.bearer());
    assertThat(byUser.json().get("items")).hasSize(12);
    Http.Response range =
        http.get(
            "/api/v1/admin/audit-events?from=2026-01-01T02:00:00Z&to=2026-01-01T05:00:00Z&user_id="
                + analyst.id(),
            session.bearer());
    assertThat(range.json().get("items")).hasSize(3);
    Http.Response entity =
        http.get(
            "/api/v1/admin/audit-events?entity_type=sample&entity_id=entity-4", session.bearer());
    assertThat(entity.json().get("items")).hasSize(1);

    Http.Response page =
        http.get("/api/v1/admin/audit-events?limit=5&user_id=" + analyst.id(), session.bearer());
    assertThat(page.json().get("items")).hasSize(5);
    Http.Response next =
        http.get(
            "/api/v1/admin/audit-events?limit=5&user_id="
                + analyst.id()
                + "&cursor="
                + page.json().get("next_cursor").asString(),
            session.bearer());
    assertThat(next.json().get("items").get(0).get("event_type").asString())
        .isEqualTo(types[6].name());
    assertThat(http.get("/api/v1/admin/audit-events?from=2026-01-01", session.bearer()).status())
        .isEqualTo(422);
    assertThat(http.get("/api/v1/admin/audit-events?event_type=LOGIN", session.bearer()).status())
        .isEqualTo(422);
  }

  @Test
  void anotherInstitutionsEventsAreInvisible() {
    UUID mine = DB.createInstitution("mine-" + System.nanoTime() % 100000);
    UUID theirs = DB.createInstitution("theirs-" + System.nanoTime() % 100000);
    tenants.runInTenant(
        theirs,
        () ->
            audit.record(
                AuditEvent.of(theirs, AuditEventType.RULE_CHANGE, "SECRET_EVENT")
                    .entity("rule", "r1")
                    .at(Instant.now())));
    Session session = issueSession(createAccount(mine, "ADMIN", "ACTIVE"));
    assertThat(http.get("/api/v1/admin/audit-events?limit=200", session.bearer()).body())
        .doesNotContain("SECRET_EVENT");
  }

  @Test
  void apiHasNoWayToChangeOrDeleteAuditEvents() {
    Session session = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    assertThat(http.send("DELETE", "/api/v1/admin/audit-events", null, session.bearer()).status())
        .isEqualTo(403);
    assertThat(http.send("PATCH", "/api/v1/admin/audit-events", "{}", session.bearer()).status())
        .isEqualTo(403);
  }
}
