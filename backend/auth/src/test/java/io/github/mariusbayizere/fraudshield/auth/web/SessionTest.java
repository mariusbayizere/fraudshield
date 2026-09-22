package io.github.mariusbayizere.fraudshield.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Refresh rotation, reuse detection, sign-out and session invalidation (FR-07-04, FR-07-09, D-27).
 */
class SessionTest extends AuthIntegrationTest {

  private Http.Response me(Session session) {
    return http.get("/api/v1/auth/me", session.bearer());
  }

  @Test
  @Tag("FR-07-04")
  void refreshRotatesTheTokenAndReuseRevokesTheWholeFamily() {
    Account account = createAccount(BANK_A, "SENIOR_ANALYST", "ACTIVE");
    Session first = login(account.email());

    Http.Response rotated = http.post("/api/v1/auth/refresh", null, first.refreshHeaders());
    assertThat(rotated.status()).isEqualTo(200);
    Session second = session(rotated);
    assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
    assertThat(me(second).status()).isEqualTo(200);

    Http.Response reused = http.post("/api/v1/auth/refresh", null, first.refreshHeaders());
    assertThat(reused.status()).as("reuse of a rotated token").isEqualTo(401);
    assertThat(http.post("/api/v1/auth/refresh", null, second.refreshHeaders()).status())
        .as("the legitimate successor is revoked with the family")
        .isEqualTo(401);
    assertThat(me(second).status()).as("and its access token ends with the session").isEqualTo(401);
    assertThat(auditActions(first.sessionIdFromToken())).contains("AUTH/REFRESH_TOKEN_REUSED");
  }

  @Test
  @Tag("D-27")
  void refreshNeedsTheDoubleSubmitCsrfToken() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    Http.Response noHeader =
        http.post(
            "/api/v1/auth/refresh",
            null,
            "Cookie",
            "fs_refresh=" + session.refreshToken() + "; fs_csrf=" + session.csrfToken());
    assertThat(noHeader.status()).isEqualTo(403);
    Http.Response mismatch =
        http.post(
            "/api/v1/auth/refresh",
            null,
            "X-CSRF-Token",
            "x".repeat(43),
            "Cookie",
            "fs_refresh=" + session.refreshToken() + "; fs_csrf=" + session.csrfToken());
    assertThat(mismatch.status()).isEqualTo(403);
    assertThat(http.post("/api/v1/auth/refresh", null, session.refreshHeaders()).status())
        .isEqualTo(200);
  }

  @Test
  @Tag("FR-07-04")
  void refreshWithoutOrWithUnknownCookieIsUnauthorized() {
    String csrf = "c".repeat(43);
    assertThat(
            http.post(
                    "/api/v1/auth/refresh", null, "X-CSRF-Token", csrf, "Cookie", "fs_csrf=" + csrf)
                .status())
        .isEqualTo(401);
    assertThat(
            http.post(
                    "/api/v1/auth/refresh",
                    null,
                    "X-CSRF-Token",
                    csrf,
                    "Cookie",
                    "fs_refresh=unknown-token; fs_csrf=" + csrf)
                .status())
        .isEqualTo(401);
  }

  @Test
  @Tag("FR-07-04")
  void accessTokenExpiresAfterFifteenMinutesAndTheFamilyAfterSevenDays() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    clock.advance(Duration.ofMinutes(14).plusSeconds(59));
    assertThat(me(session).status()).isEqualTo(200);
    clock.advance(Duration.ofSeconds(1));
    assertThat(me(session).status()).as("expired access token").isEqualTo(401);

    Session refreshed = session(http.post("/api/v1/auth/refresh", null, session.refreshHeaders()));
    assertThat(me(refreshed).status()).isEqualTo(200);
    clock.advance(Duration.ofDays(7));
    assertThat(http.post("/api/v1/auth/refresh", null, refreshed.refreshHeaders()).status())
        .as("rotation does not extend the seven-day sign-in")
        .isEqualTo(401);
  }

  @Test
  @Tag("FR-07-09")
  void logoutEndsThisSessionOnly() {
    Account account = createAccount(BANK_A, "RISK_OFFICER", "ACTIVE");
    Session phone = login(account.email());
    Session desk = login(account.email());
    Http.Response logout = http.post("/api/v1/auth/logout", null, phone.bearerWithCsrf());
    assertThat(logout.status()).isEqualTo(204);
    assertThat(logout.setCookies())
        .anySatisfy(c -> assertThat(c).startsWith("fs_refresh=;").contains("Max-Age=0"));
    assertThat(me(phone).status()).isEqualTo(401);
    assertThat(http.post("/api/v1/auth/refresh", null, phone.refreshHeaders()).status())
        .isEqualTo(401);
    assertThat(me(desk).status()).isEqualTo(200);
  }

  @Test
  @Tag("D-27")
  void logoutEverywhereEndsEverySession() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session phone = login(account.email());
    Session desk = login(account.email());
    assertThat(http.post("/api/v1/auth/logout-all", null, desk.bearerWithCsrf()).status())
        .isEqualTo(204);
    assertThat(me(phone).status()).isEqualTo(401);
    assertThat(me(desk).status()).isEqualTo(401);
    assertThat(http.post("/api/v1/auth/refresh", null, phone.refreshHeaders()).status())
        .isEqualTo(401);
    assertThat(auditActions(account.id())).contains("AUTH/LOGOUT_EVERYWHERE");
  }

  @Test
  @Tag("FR-07-09")
  void passwordChangeEndsEverySessionAndOldJwtReturns401() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    Http.Response wrong =
        http.send(
            "PUT",
            "/api/v1/auth/password",
            Map.of("current_password", "Wr0ng!Password", "new_password", "N3w!Passw0rd"),
            session.bearerWithCsrf());
    assertThat(wrong.status()).isEqualTo(401);
    Http.Response weak =
        http.send(
            "PUT",
            "/api/v1/auth/password",
            Map.of("current_password", PASSWORD, "new_password", "weakpassword"),
            session.bearerWithCsrf());
    assertThat(weak.status()).isEqualTo(422);
    assertThat(weak.json().get("errors").get(0).get("code").asString())
        .isEqualTo("password_policy");
    assertThat(weak.json().get("errors").get(0).get("message").asString()).startsWith("UPPER");

    Http.Response changed =
        http.send(
            "PUT",
            "/api/v1/auth/password",
            Map.of("current_password", PASSWORD, "new_password", "N3w!Passw0rd"),
            session.bearerWithCsrf());
    assertThat(changed.status()).isEqualTo(204);
    assertThat(me(session).status()).as("old JWT after password change").isEqualTo(401);
    assertThat(
            http.post("/api/v1/auth/login", Map.of("email", account.email(), "password", PASSWORD))
                .status())
        .isEqualTo(401);
    assertThat(
            http.post(
                    "/api/v1/auth/login",
                    Map.of("email", account.email(), "password", "N3w!Passw0rd"))
                .status())
        .isEqualTo(200);
  }

  @Test
  @Tag("FR-06-02")
  void deactivationEndsTheSessionThroughTheTokenVersion() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    assertThat(me(session).status()).isEqualTo(200);
    superuser(
        "UPDATE fraudshield.users SET status = 'DEACTIVATED', token_version = token_version + 1"
            + " WHERE id = ?",
        account.id());
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    int status = 200;
    while (System.nanoTime() < deadline && status == 200) {
      status = me(session).status();
    }
    assertThat(status)
        .as("refused within 5 seconds even without the pub/sub announcement")
        .isEqualTo(401);
  }

  @Test
  void tamperedOrForeignTokensAreRefused() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    String[] parts = session.accessToken().split("\\.");
    String forgedPayload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                new String(
                        java.util.Base64.getUrlDecoder().decode(parts[1]),
                        java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"ANALYST\"", "\"ADMIN\"")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String forged = parts[0] + "." + forgedPayload + "." + parts[2];
    assertThat(http.get("/api/v1/auth/me", "Authorization", "Bearer " + forged).status())
        .isEqualTo(401);
    String none = "eyJhbGciOiJub25lIn0." + parts[1] + ".";
    assertThat(http.get("/api/v1/auth/me", "Authorization", "Bearer " + none).status())
        .isEqualTo(401);
    assertThat(http.get("/api/v1/auth/me").status()).isEqualTo(401);
  }

  @Test
  void jwksPublishesOnlyPublicKeys() {
    Http.Response jwks = http.get("/api/v1/auth/jwks.json");
    assertThat(jwks.status()).isEqualTo(200);
    var key = jwks.json().get("keys").get(0);
    assertThat(key.get("kid").asString()).isEqualTo("staff-1");
    assertThat(key.get("kty").asString()).isEqualTo("RSA");
    assertThat(key.has("d")).as("no private exponent").isFalse();
    assertThat(key.has("p")).isFalse();
  }
}
