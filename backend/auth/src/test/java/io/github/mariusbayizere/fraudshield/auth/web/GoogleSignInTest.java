package io.github.mariusbayizere.fraudshield.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.FakeGoogle;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Google sign-in against a local fake of Google's endpoints (FR-07-03, FR-07-09, D-23). */
@Tag("FR-07-03")
class GoogleSignInTest extends AuthIntegrationTest {

  private static final String VERIFIER = "v".repeat(43);

  private Http.Response google() {
    return http.post(
        "/api/v1/auth/google",
        Map.of(
            "authorization_code",
            "4/0AfJohXtest-code",
            "code_verifier",
            VERIFIER,
            "redirect_uri",
            FakeGoogle.REDIRECT_URI));
  }

  @Test
  @Tag("D-23")
  void newGoogleUserOnAnAllowedDomainCreatesAnAnalystAccountPendingApproval() {
    String email = uniqueEmail("google.new");
    String sub = "g-" + UUID.randomUUID();
    GOOGLE.nextIdentity(sub, email, true, "Aline", "Mukamana");
    Http.Response response = google();
    assertThat(response.status()).isEqualTo(202);
    assertThat(response.json().get("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(query("SELECT role FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo("ANALYST");
    assertThat(query("SELECT status FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo("PENDING_APPROVAL");
    assertThat(
            query(
                "SELECT first_name || ' ' || last_name FROM fraudshield.users WHERE email = ?",
                email))
        .isEqualTo("Aline Mukamana");
    assertThat((String) query("SELECT avatar_url FROM fraudshield.users WHERE email = ?", email))
        .contains(sub);
    assertThat(query("SELECT oauth_id FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo(sub);

    GOOGLE.nextIdentity(sub, email, true, "Aline", "Mukamana");
    Http.Response again = google();
    assertThat(again.status()).as("no access until an administrator approves").isEqualTo(403);
    assertThat(again.problemType()).isEqualTo("urn:fraudshield:problem:account-not-active");
  }

  @Test
  @Tag("D-23")
  void googleUserOutsideTheAllowlistIsRefusedAndNothingIsCreated() {
    String email = "someone." + UUID.randomUUID() + "@gmail.com";
    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), email, true, "Some", "One");
    int revokedBefore = GOOGLE.revoked().size();
    assertThat(google().status()).isEqualTo(403);
    assertThat(query("SELECT count(*) FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo(0L);
    assertThat(GOOGLE.revoked())
        .as("the Google token is revoked at once")
        .hasSize(revokedBefore + 1);
  }

  @Test
  void existingActiveAccountLinksByVerifiedEmailSignsInAndLogoutRevokesTheGoogleToken() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    String sub = "g-" + UUID.randomUUID();
    GOOGLE.nextIdentity(sub, account.email(), true, "Amani", "Uwase");
    Http.Response response = google();
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("user").get("linked_providers").get(0).asString())
        .isEqualTo("GOOGLE");
    assertThat(query("SELECT oauth_id FROM fraudshield.users WHERE id = ?", account.id()))
        .isEqualTo(sub);
    assertThat(auditActions(account.id()))
        .contains("AUTH/GOOGLE_ACCOUNT_LINKED", "AUTH/GOOGLE_SIGN_IN");

    Session session = session(response);
    int revokedBefore = GOOGLE.revoked().size();
    assertThat(http.post("/api/v1/auth/logout", null, session.bearerWithCsrf()).status())
        .isEqualTo(204);
    assertThat(GOOGLE.revoked())
        .as("FR-07-09: Google revocation API called on logout")
        .hasSize(revokedBefore + 1)
        .last()
        .asString()
        .startsWith("ya29.test-access-token-");
  }

  @Test
  void linkToAnotherGoogleAccountIsRefused() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    GOOGLE.nextIdentity("g-first-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    assertThat(google().status()).isEqualTo(200);
    GOOGLE.nextIdentity("g-other-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    assertThat(google().status()).isEqualTo(401);
  }

  @Test
  @Tag("FR-06-02")
  void deactivatedAccountIsBlockedFromGoogleSignIn() {
    Account account = createAccount(BANK_A, "ANALYST", "DEACTIVATED");
    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    Http.Response response = google();
    assertThat(response.status()).isEqualTo(403);
    assertThat(response.problemType()).isEqualTo("urn:fraudshield:problem:account-not-active");
  }

  @Test
  void invalidTokensReturn401() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), false, "Amani", "Uwase");
    assertThat(google().status()).as("email_verified false").isEqualTo(401);

    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    GOOGLE.nextAudience("someone-else.apps.googleusercontent.com");
    assertThat(google().status()).as("wrong audience").isEqualTo(401);

    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    GOOGLE.signWithForeignKey();
    assertThat(google().status()).as("signature from an unpublished key").isEqualTo(401);

    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    GOOGLE.tokenStatus(400);
    assertThat(google().status()).as("code refused by Google").isEqualTo(401);
  }

  @Test
  void googleOutageIsServiceUnavailableWithRetryAfter() {
    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), uniqueEmail("outage"), true, "Amani", "Uwase");
    GOOGLE.tokenStatus(503);
    Http.Response response = google();
    assertThat(response.status()).isEqualTo(503);
    assertThat(response.header("Retry-After")).isPresent();
  }

  @Test
  void unregisteredRedirectUriIsRejected() {
    Http.Response response =
        http.post(
            "/api/v1/auth/google",
            Map.of(
                "authorization_code", "4/0AfJohXtest-code",
                "code_verifier", VERIFIER,
                "redirect_uri", "https://evil.example/callback"));
    assertThat(response.status()).isEqualTo(422);
    assertThat(response.json().get("errors").get(0).get("field").asString())
        .isEqualTo("redirect_uri");
  }

  @Test
  void signInTakesUnderThreeSeconds() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    GOOGLE.nextIdentity("g-" + UUID.randomUUID(), account.email(), true, "Amani", "Uwase");
    long start = System.nanoTime();
    assertThat(google().status()).isEqualTo(200);
    assertThat((System.nanoTime() - start) / 1_000_000)
        .as("FR-07-03 flow < 3 s (server side, local fake)")
        .isLessThan(3000);
  }
}
