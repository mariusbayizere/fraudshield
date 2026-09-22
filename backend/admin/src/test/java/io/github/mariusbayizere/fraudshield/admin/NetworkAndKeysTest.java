package io.github.mariusbayizere.fraudshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Office IP allowlist and API-key administration over HTTP (D-26, FR-06-07, FR-07-05). */
class NetworkAndKeysTest extends AuthIntegrationTest {

  @Test
  @Tag("D-26")
  void allowlistEntriesAreValidatedAddedListedAndRemovedWithAudit() {
    Account admin =
        createAccount(DB.createInstitution("net-" + System.nanoTime() % 100000), "ADMIN", "ACTIVE");
    Session session = issueSession(admin);
    Http.Response added =
        http.post(
            "/api/v1/admin/ip-allowlist",
            Map.of("cidr", "196.12.144.0/24", "description", "Kigali HQ"),
            session.bearer());
    assertThat(added.status()).isEqualTo(201);
    assertThat(added.json().get("created_by").get("user_id").asString())
        .isEqualTo(admin.id().toString());
    final String entryId = added.json().get("entry_id").asString();
    assertThat(
            http.post(
                    "/api/v1/admin/ip-allowlist",
                    Map.of("cidr", "196.12.144.0/24", "description", "Again"),
                    session.bearer())
                .status())
        .isEqualTo(409);
    for (String bad :
        List.of(
            "196.12.144.1/24",
            "300.1.1.1/8",
            "10.0.0.0/33",
            "2001:db8::1/64",
            "::ffff:1.2.3.4/32",
            "10.0.0.0",
            "a.b.c.d/8",
            "010.0.0.0/8")) {
      Http.Response rejected =
          http.post(
              "/api/v1/admin/ip-allowlist",
              Map.of("cidr", bad, "description", "Bad range"),
              session.bearer());
      assertThat(rejected.status()).as(bad).isEqualTo(422);
      assertThat(rejected.json().get("errors").get(0).get("code").asString())
          .as(bad)
          .isEqualTo("invalid_cidr");
    }
    assertThat(
            http.post(
                    "/api/v1/admin/ip-allowlist",
                    Map.of("cidr", "2001:db8:1::/48", "description", "IPv6 office"),
                    session.bearer())
                .status())
        .isEqualTo(201);
    assertThat(http.get("/api/v1/admin/ip-allowlist", session.bearer()).json()).hasSize(2);
    assertThat(
            http.send("DELETE", "/api/v1/admin/ip-allowlist/" + entryId, null, session.bearer())
                .status())
        .isEqualTo(204);
    assertThat(
            http.send("DELETE", "/api/v1/admin/ip-allowlist/" + entryId, null, session.bearer())
                .status())
        .isEqualTo(404);
    assertThat(auditActions(entryId))
        .containsExactly(
            "USER_ADMIN/IP_ALLOWLIST_ENTRY_ADDED", "USER_ADMIN/IP_ALLOWLIST_ENTRY_REMOVED");
  }

  @Test
  @Tag("FR-06-07")
  void rawKeyIsShownOnceListShowsOnlyNameScopesAndLastFour() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Http.Response created =
        http.post(
            "/api/v1/admin/api-keys",
            Map.of(
                "name",
                "Core banking prod " + System.nanoTime() % 100000,
                "scopes",
                List.of("ingest:write", "decisions:read")),
            admin.bearer());
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.header("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    String raw = created.json().get("raw_key").asString();
    assertThat(raw).matches("^fsk_test_[a-z0-9]{12}_[A-Za-z0-9_-]{43}$");
    String keyId = created.json().get("key_id").asString();
    String list = http.get("/api/v1/admin/api-keys", admin.bearer()).body();
    assertThat(list)
        .contains(keyId)
        .contains(raw.substring(raw.length() - 4))
        .doesNotContain(raw.substring(raw.lastIndexOf('_') + 1));

    assertThat(http.post("/api/v1/transactions/ingest", "{}", "X-API-Key", raw).status())
        .as("the key reaches its scope's operation (no handler yet in M7)")
        .isNotIn(401, 403);
    assertThat(http.get("/api/v1/alerts", "X-API-Key", raw).status()).as("FR-07-05").isEqualTo(403);
    assertThat(http.get("/api/v1/admin/users", "X-API-Key", raw).status())
        .as("FR-07-05")
        .isEqualTo(403);
    assertThat(
            http.get("/api/v1/jobs/2d1f0f5e-7c1b-4c2a-9d57-3a1c9a4b2e10", "X-API-Key", raw)
                .status())
        .as("scope jobs:read not granted")
        .isEqualTo(403);
  }

  @Test
  @Tag("FR-06-07")
  void revokedKeyReturns401WithinFiveSecondsAndRotationOverlaps() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Http.Response created =
        http.post(
            "/api/v1/admin/api-keys",
            Map.of(
                "name", "Rotating " + System.nanoTime() % 100000, "scopes", List.of("jobs:read")),
            admin.bearer());
    String keyId = created.json().get("key_id").asString();
    String original = created.json().get("raw_key").asString();
    String job = "/api/v1/jobs/2d1f0f5e-7c1b-4c2a-9d57-3a1c9a4b2e10";
    assertThat(http.get(job, "X-API-Key", original).status()).isNotIn(401, 403);

    Http.Response rotated =
        http.post("/api/v1/admin/api-keys/" + keyId + "/rotate", null, admin.bearer());
    assertThat(rotated.status()).isEqualTo(201);
    String replacement = rotated.json().get("raw_key").asString();
    assertThat(http.get(job, "X-API-Key", original).status())
        .as("24-hour overlap")
        .isNotIn(401, 403);
    assertThat(http.get(job, "X-API-Key", replacement).status()).isNotIn(401, 403);
    String state =
        http.get("/api/v1/admin/api-keys", admin.bearer())
            .json()
            .valueStream()
            .filter(k -> k.get("key_id").asString().equals(keyId))
            .findFirst()
            .orElseThrow()
            .get("state")
            .asString();
    assertThat(state).isEqualTo("ROTATING");
    assertThat(
            http.post("/api/v1/admin/api-keys/" + keyId + "/rotate", null, admin.bearer()).status())
        .isEqualTo(409);

    String replacementId = rotated.json().get("key_id").asString();
    assertThat(
            http.post("/api/v1/admin/api-keys/" + replacementId + "/revoke", null, admin.bearer())
                .status())
        .isEqualTo(204);
    long start = System.nanoTime();
    int status = 0;
    while (System.nanoTime() - start < Duration.ofSeconds(5).toNanos()) {
      status = http.get(job, "X-API-Key", replacement).status();
      if (status == 401) {
        break;
      }
    }
    assertThat(status).as("revoked key returns 401 within 5 seconds").isEqualTo(401);
    assertThat(auditActions(keyId))
        .contains("API_KEY_LIFECYCLE/API_KEY_CREATED", "API_KEY_LIFECYCLE/API_KEY_ROTATED");
    assertThat(auditActions(replacementId)).contains("API_KEY_LIFECYCLE/API_KEY_REVOKED");
  }

  @Test
  void keyCreationValidatesTheContract() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Http.Response duplicates =
        http.post(
            "/api/v1/admin/api-keys",
            Map.of("name", "Dup scopes", "scopes", List.of("jobs:read", "jobs:read")),
            admin.bearer());
    assertThat(duplicates.status()).isEqualTo(422);
    assertThat(duplicates.json().get("errors").get(0).get("code").asString())
        .isEqualTo("duplicate_items");
    Http.Response badScope =
        http.post(
            "/api/v1/admin/api-keys",
            Map.of("name", "Bad scope", "scopes", List.of("admin:all")),
            admin.bearer());
    assertThat(badScope.status()).isEqualTo(422);
    Http.Response insecure =
        http.post(
            "/api/v1/admin/api-keys",
            Map.of(
                "name",
                "Insecure hook",
                "scopes",
                List.of("ingest:write"),
                "webhook_url",
                "https://127.0.0.1/hook"),
            admin.bearer());
    assertThat(insecure.status()).isEqualTo(422);
    assertThat(insecure.json().get("errors").get(0).get("code").asString())
        .isEqualTo("webhook_url_not_allowed");
    assertThat(
            http.post("/api/v1/admin/api-keys/nosuchkey000/revoke", null, admin.bearer()).status())
        .isEqualTo(404);
  }
}
