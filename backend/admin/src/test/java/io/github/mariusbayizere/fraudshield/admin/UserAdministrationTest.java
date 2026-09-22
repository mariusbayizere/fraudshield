package io.github.mariusbayizere.fraudshield.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Staff account administration and approvals (FR-06-01, FR-06-02, D-23, D-24, ADR 0014). */
class UserAdministrationTest extends AuthIntegrationTest {

  private Map<String, Object> newUser(String email, String employeeId, String role) {
    Map<String, Object> body = new HashMap<>();
    body.put("first_name", "Claudine");
    body.put("last_name", "Uwimana");
    body.put("email", email);
    body.put("phone", "+250788111222");
    body.put("employee_id", employeeId);
    body.put("department", "RISK");
    body.put("role", role);
    body.put("preferred_locale", "fr");
    return body;
  }

  private Http.Response patch(Session admin, UUID userId, Map<String, Object> body) {
    return http.send("PATCH", "/api/v1/admin/users/" + userId, body, admin.bearer());
  }

  private long version(Session admin, UUID userId) {
    Http.Response user = http.get("/api/v1/admin/users/" + userId, admin.bearer());
    return Long.parseLong(user.header("ETag").orElseThrow().replace("\"", ""));
  }

  @Test
  @Tag("FR-06-01")
  void adminCreatesAccountThatSignsInWithTheEmailedTemporaryPassword() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    String email = uniqueEmail("created");
    String employeeId = uniqueEmployeeId();
    UUID key = UUID.randomUUID();
    Http.Response created =
        http.post(
            "/api/v1/admin/users", newUser(email, employeeId, "RISK_OFFICER"), headers(admin, key));
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.json().get("role").asString()).isEqualTo("RISK_OFFICER");
    assertThat(created.json().get("status").asString()).isEqualTo("ACTIVE");
    assertThat(created.json().get("phone").asString()).isEqualTo("+250788111222");
    String userId = created.json().get("user_id").asString();

    Http.Response replay =
        http.post(
            "/api/v1/admin/users", newUser(email, employeeId, "RISK_OFFICER"), headers(admin, key));
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.json().get("user_id").asString()).as("idempotent replay").isEqualTo(userId);
    assertThat(
            http.post(
                    "/api/v1/admin/users",
                    newUser(email, employeeId, "ANALYST"),
                    headers(admin, key))
                .status())
        .as("same key, different request")
        .isEqualTo(409);
    assertThat(
            http.post(
                    "/api/v1/admin/users",
                    newUser(email, uniqueEmployeeId(), "ANALYST"),
                    headers(admin, UUID.randomUUID()))
                .status())
        .as("UNIQUE(email)")
        .isEqualTo(409);
    assertThat(
            http.post(
                    "/api/v1/admin/users",
                    newUser(uniqueEmail("x"), employeeId, "ANALYST"),
                    headers(admin, UUID.randomUUID()))
                .status())
        .as("UNIQUE(institution, employee_id)")
        .isEqualTo(409);

    var welcome = mailer.await(email, StaffMailer.Template.WELCOME, Duration.ofSeconds(10));
    assertThat(welcome).isPresent();
    String temporary = welcome.get().message().secret();
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", email, "password", temporary)).status())
        .isEqualTo(200);
    assertThat(auditActions(userId)).contains("USER_ADMIN/USER_CREATED");
    assertThat(
            http.post(
                    "/api/v1/admin/users",
                    newUser(uniqueEmail("y"), uniqueEmployeeId(), "ANALYST"),
                    admin.bearer())
                .status())
        .as("Idempotency-Key is required")
        .isEqualTo(400);
  }

  private static String[] headers(Session session, UUID key) {
    return new String[] {
      "Authorization", "Bearer " + session.accessToken(), "Idempotency-Key", key.toString()
    };
  }

  @Test
  @Tag("FR-06-02")
  void roleChangeEndsTheOldTokenAndStaleVersionsConflict() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account analyst = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session analystSession = issueSession(analyst);
    long before = version(admin, analyst.id());

    Http.Response promoted =
        patch(admin, analyst.id(), Map.of("version", before, "role", "SENIOR_ANALYST"));
    assertThat(promoted.status()).isEqualTo(200);
    assertThat(promoted.json().get("role").asString()).isEqualTo("SENIOR_ANALYST");
    assertThat(promoted.header("ETag")).hasValue("\"" + (before + 1) + "\"");
    assertThat(http.get("/api/v1/auth/me", analystSession.bearer()).status())
        .as("role claim is immutable: the old token ends")
        .isEqualTo(401);

    Http.Response stale = patch(admin, analyst.id(), Map.of("version", before, "department", "IT"));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.problemType()).isEqualTo("urn:fraudshield:problem:conflict");
    assertThat(stale.json().get("current").get("role").asString()).isEqualTo("SENIOR_ANALYST");

    Http.Response nothing = patch(admin, analyst.id(), Map.of("version", before + 1));
    assertThat(nothing.status()).as("minProperties 2").isEqualTo(400);
    assertThat(auditActions(analyst.id())).contains("USER_ADMIN/USER_ROLE_CHANGED");
  }

  @Test
  @Tag("FR-07-01")
  void adminCannotChangeOwnRoleOrStatus() {
    Account admin =
        createAccount(
            DB.createInstitution("self-" + System.nanoTime() % 100000), "ADMIN", "ACTIVE");
    Session session = issueSession(admin);
    long version = version(session, admin.id());
    Http.Response role =
        patch(session, admin.id(), Map.of("version", version, "role", "RISK_OFFICER"));
    assertThat(role.status()).isEqualTo(403);
    assertThat(role.problemType()).isEqualTo("urn:fraudshield:problem:self-modification");
    Http.Response status =
        patch(session, admin.id(), Map.of("version", version, "status", "DEACTIVATED"));
    assertThat(status.status()).isEqualTo(403);
    assertThat(
            patch(session, admin.id(), Map.of("version", version, "first_name", "Renamed"))
                .status())
        .as("own profile fields are fine")
        .isEqualTo(200);
    assertThat(auditActions(admin.id()))
        .as("review finding 5: refusals are audited")
        .contains("USER_ADMIN/USER_CHANGE_REFUSED");
  }

  @Test
  @Tag("FR-06-06")
  void wrongRoleRefusalsAreAudited() {
    Account analyst = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = issueSession(analyst);
    assertThat(http.get("/api/v1/admin/users", session.bearer()).status()).isEqualTo(403);
    assertThat(
            query(
                "SELECT count(*) FROM fraudshield.audit_events WHERE user_id = ?"
                    + " AND action = 'ACCESS_DENIED'",
                analyst.id()))
        .isEqualTo(1L);
  }

  @Test
  @Tag("D-26")
  void lockingFailureLockedAccountMakesItAnAdministratorsLock() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account locked = createAccount(BANK_A, "ANALYST", "LOCKED");
    Http.Response response =
        patch(
            admin, locked.id(), Map.of("version", version(admin, locked.id()), "status", "LOCKED"));
    assertThat(response.status()).isEqualTo(200);
    assertThat(
            query(
                "SELECT locked_until > now() + interval '1 year' FROM fraudshield.users"
                    + " WHERE id = ?",
                locked.id()))
        .as("review finding 12: an administrator's lock does not lapse after 30 minutes")
        .isEqualTo(true);
    assertThat(auditActions(locked.id())).contains("USER_ADMIN/USER_LOCKED");
  }

  @Test
  @Tag("FR-07-01")
  void adminDemotedInsideTheCacheWindowCannotAct() {
    Account admin = createAccount(BANK_A, "ADMIN", "ACTIVE");
    Session session = issueSession(admin);
    Account analyst = createAccount(BANK_A, "ANALYST", "ACTIVE");
    // Demoted in the database without announcing it: the token is still accepted for up to the
    // session-cache bound, but administrator writes re-check the role.
    superuser("UPDATE fraudshield.users SET role = 'ANALYST' WHERE id = ?", admin.id());
    Http.Response write = patch(session, analyst.id(), Map.of("version", 0, "department", "IT"));
    assertThat(write.status()).isIn(401, 403);
  }

  @Test
  @Tag("FR-07-01")
  void concurrentMutualDemotionLeavesOneActiveAdmin() throws Exception {
    UUID institution = DB.createInstitution("race-" + System.nanoTime() % 100000);
    Account first = createAccount(institution, "ADMIN", "ACTIVE");
    Account second = createAccount(institution, "ADMIN", "ACTIVE");
    Session firstSession = issueSession(first);
    Session secondSession = issueSession(second);
    long firstVersion = version(secondSession, first.id());
    long secondVersion = version(firstSession, second.id());
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return patch(
                        firstSession,
                        second.id(),
                        Map.of("version", secondVersion, "role", "ANALYST"))
                    .status();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return patch(
                        secondSession,
                        first.id(),
                        Map.of("version", firstVersion, "role", "ANALYST"))
                    .status();
              });
      start.countDown();
      java.util.List<Integer> statuses = java.util.List.of(a.get(), b.get());
      assertThat(statuses).as("exactly one demotion succeeds").containsOnlyOnce(200);
      assertThat(statuses.stream().filter(s -> s != 200).findFirst().orElseThrow())
          .isIn(401, 403, 409);
    }
    assertThat(
            query(
                "SELECT count(*) FROM fraudshield.users WHERE institution_id = ? AND role = 'ADMIN'"
                    + " AND status = 'ACTIVE'",
                institution))
        .isEqualTo(1L);
  }

  @Test
  @Tag("FR-06-02")
  void deactivationEndsSessionsAndBlocksPasswordSignIn() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account analyst = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session analystSession = issueSession(analyst);
    Http.Response deactivated =
        patch(
            admin,
            analyst.id(),
            Map.of("version", version(admin, analyst.id()), "status", "DEACTIVATED"));
    assertThat(deactivated.status()).isEqualTo(200);
    assertThat(http.get("/api/v1/auth/me", analystSession.bearer()).status()).isEqualTo(401);
    assertThat(http.post("/api/v1/auth/refresh", null, analystSession.refreshHeaders()).status())
        .isEqualTo(401);
    Http.Response signIn =
        http.post("/api/v1/auth/login", Map.of("email", analyst.email(), "password", PASSWORD));
    assertThat(signIn.status()).isEqualTo(403);

    Http.Response locked =
        patch(
            admin,
            analyst.id(),
            Map.of("version", version(admin, analyst.id()), "status", "LOCKED"));
    assertThat(locked.status()).isEqualTo(200);
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", analyst.email(), "password", PASSWORD))
                .status())
        .as("an administrator's lock does not lift after 30 minutes")
        .isEqualTo(423);
    clock.advance(Duration.ofHours(2));
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", analyst.email(), "password", PASSWORD))
                .status())
        .isEqualTo(423);
    clock.set(java.time.Instant.now());
    Session admin2 = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Http.Response reactivated =
        patch(
            admin2,
            analyst.id(),
            Map.of("version", version(admin2, analyst.id()), "status", "ACTIVE"));
    assertThat(reactivated.status()).isEqualTo(200);
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", analyst.email(), "password", PASSWORD))
                .status())
        .isEqualTo(200);
    assertThat(auditActions(analyst.id()))
        .contains(
            "USER_ADMIN/USER_DEACTIVATED", "USER_ADMIN/USER_LOCKED", "USER_ADMIN/USER_REACTIVATED");
  }

  @Test
  void anotherInstitutionsAccountsAreNotFound() {
    Session adminA = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account inB = createAccount(BANK_B, "ANALYST", "ACTIVE");
    assertThat(http.get("/api/v1/admin/users/" + inB.id(), adminA.bearer()).status())
        .isEqualTo(404);
    assertThat(patch(adminA, inB.id(), Map.of("version", 0, "status", "DEACTIVATED")).status())
        .isEqualTo(404);
    Http.Response list = http.get("/api/v1/admin/users?limit=200", adminA.bearer());
    assertThat(list.json().get("items").valueStream().map(u -> u.get("user_id").asString()))
        .doesNotContain(inB.id().toString());
  }

  @Test
  void listPaginatesWithCursorAndFilters() {
    UUID institution = DB.createInstitution("paging-" + System.nanoTime() % 100000);
    Session admin = issueSession(createAccount(institution, "ADMIN", "ACTIVE"));
    for (int i = 0; i < 5; i++) {
      createAccount(institution, "ANALYST", "ACTIVE");
    }
    createAccount(institution, "RISK_OFFICER", "DEACTIVATED");
    Http.Response first = http.get("/api/v1/admin/users?limit=3", admin.bearer());
    assertThat(first.json().get("items")).hasSize(3);
    String cursor = first.json().get("next_cursor").asString();
    Http.Response second = http.get("/api/v1/admin/users?limit=3&cursor=" + cursor, admin.bearer());
    assertThat(second.json().get("items")).hasSize(3);
    Http.Response last =
        http.get(
            "/api/v1/admin/users?limit=3&cursor=" + second.json().get("next_cursor").asString(),
            admin.bearer());
    assertThat(last.json().get("items")).hasSize(1);
    assertThat(last.json().get("next_cursor").isNull()).isTrue();
    assertThat(http.get("/api/v1/admin/users?role=ANALYST", admin.bearer()).json().get("items"))
        .hasSize(5);
    assertThat(
            http.get("/api/v1/admin/users?status=DEACTIVATED", admin.bearer()).json().get("items"))
        .hasSize(1);
    assertThat(http.get("/api/v1/admin/users?limit=0", admin.bearer()).status()).isEqualTo(422);
    assertThat(http.get("/api/v1/admin/users?role=OWNER", admin.bearer()).status()).isEqualTo(422);
    assertThat(http.get("/api/v1/admin/users?cursor=bad!", admin.bearer()).status()).isEqualTo(422);
  }

  @Test
  @Tag("D-24")
  void approvalGrantsTheRoleOnlyAfterEmailVerificationAndRejectionDeactivates() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account pending = createAccount(BANK_A, "ANALYST", "PENDING_APPROVAL");
    superuser(
        "UPDATE fraudshield.users SET email_verified = false, requested_role = 'RISK_OFFICER'"
            + " WHERE id = ?",
        pending.id());
    assertThat(
            http.get("/api/v1/admin/approvals", admin.bearer())
                .json()
                .valueStream()
                .map(u -> u.get("user_id").asString()))
        .contains(pending.id().toString());
    String path = "/api/v1/admin/approvals/" + pending.id();
    assertThat(http.post(path, Map.of("decision", "APPROVE"), admin.bearer()).status())
        .as("granted_role required to approve")
        .isEqualTo(400);
    assertThat(
            http.post(
                    path, Map.of("decision", "APPROVE", "granted_role", "ANALYST"), admin.bearer())
                .status())
        .as("email not verified yet")
        .isEqualTo(409);
    superuser("UPDATE fraudshield.users SET email_verified = true WHERE id = ?", pending.id());
    Http.Response approved =
        http.post(
            path, Map.of("decision", "APPROVE", "granted_role", "SENIOR_ANALYST"), admin.bearer());
    assertThat(approved.status()).isEqualTo(200);
    assertThat(approved.json().get("role").asString())
        .as("the administrator grants, whatever was requested")
        .isEqualTo("SENIOR_ANALYST");
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", pending.email(), "password", PASSWORD))
                .json()
                .get("user")
                .get("role")
                .asString())
        .isEqualTo("SENIOR_ANALYST");
    assertThat(http.post(path, Map.of("decision", "REJECT"), admin.bearer()).status())
        .as("no longer pending")
        .isEqualTo(409);

    Account rejected = createAccount(BANK_A, "ANALYST", "PENDING_APPROVAL");
    Http.Response rejection =
        http.post(
            "/api/v1/admin/approvals/" + rejected.id(),
            Map.of("decision", "REJECT", "reason", "Unknown employee"),
            admin.bearer());
    assertThat(rejection.json().get("status").asString()).isEqualTo("DEACTIVATED");
    assertThat(auditActions(pending.id())).contains("USER_ADMIN/USER_APPROVED");
    assertThat(auditActions(rejected.id())).contains("USER_ADMIN/USER_REJECTED");
    assertThat(
            http.post(
                    "/api/v1/admin/approvals/" + UUID.randomUUID(),
                    Map.of("decision", "REJECT"),
                    admin.bearer())
                .status())
        .isEqualTo(404);
  }

  @Test
  void pendingAccountsLeavePendingOnlyThroughApprovals() {
    Session admin = issueSession(createAccount(BANK_A, "ADMIN", "ACTIVE"));
    Account pending = createAccount(BANK_A, "ANALYST", "PENDING_APPROVAL");
    Http.Response activate =
        patch(
            admin,
            pending.id(),
            Map.of("version", version(admin, pending.id()), "status", "ACTIVE"));
    assertThat(activate.status()).isEqualTo(409);
  }
}
