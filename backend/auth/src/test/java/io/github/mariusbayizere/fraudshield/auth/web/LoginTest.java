package io.github.mariusbayizere.fraudshield.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Sign-in, lockout and rate limits against the running application (FR-07-06, D-26). */
class LoginTest extends AuthIntegrationTest {

  private Http.Response login(String email, String password, String... headers) {
    return http.post("/api/v1/auth/login", Map.of("email", email, "password", password), headers);
  }

  @Test
  @Tag("FR-07-04")
  void signInReturnsBearerTokenAndHardenedCookies() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Http.Response response = login(account.email(), PASSWORD);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("token_type").asString()).isEqualTo("Bearer");
    assertThat(response.json().get("expires_in").asInt()).isEqualTo(900);
    assertThat(response.json().get("user").get("role").asString()).isEqualTo("ANALYST");
    assertThat(response.json().get("user").has("password_hash")).isFalse();
    assertThat(response.header("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    String refresh =
        response.setCookies().stream()
            .filter(c -> c.startsWith("fs_refresh="))
            .findFirst()
            .orElseThrow();
    assertThat(refresh)
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/api/v1/auth/refresh")
        .contains("Max-Age=604800");
    String csrf =
        response.setCookies().stream()
            .filter(c -> c.startsWith("fs_csrf="))
            .findFirst()
            .orElseThrow();
    assertThat(csrf).doesNotContain("HttpOnly").contains("SameSite=Strict").contains("Path=/");
    assertThat(auditActions(account.id())).contains("AUTH/LOGIN_SUCCEEDED");
  }

  @Test
  @Tag("FR-07-06")
  void fifthFailureLocksTheAccountAndEmailsAnUnlockLinkThatWorksOnce() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(login(account.email(), "Wr0ng!Password").status()).isEqualTo(401);
    }
    Http.Response fifth = login(account.email(), "Wr0ng!Password");
    assertThat(fifth.status()).isEqualTo(423);
    assertThat(fifth.problemType()).isEqualTo("urn:fraudshield:problem:account-locked");
    assertThat(query("SELECT status FROM fraudshield.users WHERE id = ?", account.id()))
        .isEqualTo("LOCKED");
    assertThat(login(account.email(), PASSWORD).status())
        .as("the right password does not open a locked account")
        .isEqualTo(423);

    var email =
        mailer.await(account.email(), StaffMailer.Template.ACCOUNT_LOCKED, Duration.ofSeconds(30));
    assertThat(email).as("unlock email within 30 seconds").isPresent();
    String link = email.get().message().secret();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(http.post("/api/v1/auth/unlock", Map.of("token", token)).status()).isEqualTo(204);
    assertThat(http.post("/api/v1/auth/unlock", Map.of("token", token)).status())
        .as("single use")
        .isEqualTo(410);
    assertThat(login(account.email(), PASSWORD).status()).isEqualTo(200);
    assertThat(auditActions(account.id()))
        .contains("AUTH/ACCOUNT_LOCKED", "AUTH/ACCOUNT_UNLOCKED", "AUTH/LOGIN_SUCCEEDED");
  }

  @Test
  @Tag("D-26")
  void failedSignInLockLiftsByItselfAfterThirtyMinutes() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    for (int attempt = 1; attempt <= 5; attempt++) {
      login(account.email(), "Wr0ng!Password");
    }
    clock.advance(Duration.ofMinutes(29));
    assertThat(login(account.email(), PASSWORD, "X-Forwarded-For", "ignored").status())
        .isEqualTo(423);
    clock.advance(Duration.ofMinutes(1));
    assertThat(login(account.email(), PASSWORD).status()).isEqualTo(200);
    assertThat(auditActions(account.id())).contains("AUTH/ACCOUNT_AUTO_UNLOCKED");
  }

  @Test
  @Tag("FR-07-06")
  void eleventhAttemptFromOneAddressIsRateLimitedWithRetryAfter() {
    for (int attempt = 1; attempt <= 10; attempt++) {
      assertThat(login(uniqueEmail("nobody"), "Wr0ng!Password").status()).isEqualTo(401);
    }
    Http.Response eleventh = login(uniqueEmail("nobody"), "Wr0ng!Password");
    assertThat(eleventh.status()).isEqualTo(429);
    assertThat(eleventh.problemType()).isEqualTo("urn:fraudshield:problem:rate-limited");
    assertThat(eleventh.header("Retry-After"))
        .hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isBetween(1L, 900L));
  }

  @Test
  @Tag("D-26")
  void officeAddressOnTheAllowlistGetsTheHigherCeiling() {
    Account admin = createAccount(BANK_A, "ADMIN", "ACTIVE");
    Account colleague = createAccount(BANK_A, "ANALYST", "ACTIVE");
    superuser(
        "INSERT INTO fraudshield.office_ip_allowlist"
            + " (institution_id, cidr, description, created_by)"
            + " VALUES (?, '127.0.0.0/8', 'Test office', ?)",
        BANK_A,
        admin.id());
    for (int attempt = 1; attempt <= 12; attempt++) {
      Account staff = createAccount(BANK_A, "ANALYST", "ACTIVE");
      assertThat(login(staff.email(), PASSWORD).status()).as("attempt %d", attempt).isEqualTo(200);
    }
    for (int attempt = 1; attempt <= 10; attempt++) {
      login(colleague.email(), "Wr0ng!Password");
    }
    assertThat(login(colleague.email(), PASSWORD).status())
        .as("the per-(IP, email) limit still applies behind the office address")
        .isIn(423, 429);
    superuser("DELETE FROM fraudshield.office_ip_allowlist WHERE institution_id = ?", BANK_A);
  }

  @Test
  @Tag("FR-07-01")
  void pendingAndDeactivatedAccountsAreRefusedWithoutRevealingStateToWrongPasswords() {
    Account pending = createAccount(BANK_A, "ANALYST", "PENDING_APPROVAL");
    Account deactivated = createAccount(BANK_A, "ANALYST", "DEACTIVATED");
    for (Account account : new Account[] {pending, deactivated}) {
      Http.Response right = login(account.email(), PASSWORD);
      assertThat(right.status()).isEqualTo(403);
      assertThat(right.problemType()).isEqualTo("urn:fraudshield:problem:account-not-active");
      assertThat(login(account.email(), "Wr0ng!Password").status()).isEqualTo(401);
    }
  }

  @Test
  @Tag("FR-07-06")
  void unknownEmailsBehaveLikeRealAccountsIncludingTheLock() {
    String email = uniqueEmail("ghost");
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(login(email, "Wr0ng!Password").status()).isEqualTo(401);
    }
    assertThat(login(email, "Wr0ng!Password").status()).isEqualTo(423);
  }

  @Test
  void overlongUserAgentDoesNotBreakSignIn() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Http.Response response =
        login(account.email(), PASSWORD, "User-Agent", "Mozilla/5.0 " + "x".repeat(5000));
    assertThat(response.status()).as("review finding 8").isEqualTo(200);
  }

  @Test
  @Tag("FR-07-06")
  void concurrentSignInsAllSucceed() throws Exception {
    java.util.List<Account> accounts = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      accounts.add(createAccount(BANK_A, "ANALYST", "ACTIVE"));
    }
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      java.util.List<java.util.concurrent.Callable<Integer>> calls = new java.util.ArrayList<>();
      for (Account account : accounts) {
        calls.add(() -> login(account.email(), PASSWORD).status());
      }
      for (var status : pool.invokeAll(calls)) {
        assertThat(status.get()).as("review finding 4").isEqualTo(200);
      }
    }
  }

  @Test
  @Tag("FR-07-07")
  void passwordOverSeventyTwoBytesIsInvalidCredentialsNotValidation() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Http.Response response = login(account.email(), PASSWORD + "x".repeat(70));
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.problemType()).isEqualTo("urn:fraudshield:problem:unauthorized");
  }

  @Test
  void malformedBodiesAreContractValidationProblems() {
    Http.Response unknown =
        http.post(
            "/api/v1/auth/login", Map.of("email", "a@b.rw", "password", "x", "role", "ADMIN"));
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.json().get("errors").get(0).get("code").asString())
        .isEqualTo("unknown_field");
    assertThat(unknown.json().get("correlation_id").asString()).hasSize(36);

    Http.Response missing = http.post("/api/v1/auth/login", Map.of("email", "a@b.rw"));
    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.json().get("errors").get(0).get("code").asString()).isEqualTo("required");

    Http.Response notJson = http.post("/api/v1/auth/login", "{not json");
    assertThat(notJson.status()).isEqualTo(400);
    assertThat(notJson.json().get("errors").get(0).get("code").asString())
        .isEqualTo("malformed_json");

    Http.Response badEmail =
        http.post("/api/v1/auth/login", Map.of("email", "not-an-email", "password", "x"));
    assertThat(badEmail.status()).isEqualTo(422);
    assertThat(badEmail.json().get("errors").get(0).get("code").asString())
        .isEqualTo("invalid_format");
  }
}
