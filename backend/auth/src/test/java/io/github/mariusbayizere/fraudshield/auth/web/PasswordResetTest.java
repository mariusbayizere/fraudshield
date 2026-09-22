package io.github.mariusbayizere.fraudshield.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Password reset by emailed one-time code (FR-07-08, FR-07-09). */
@Tag("FR-07-08")
class PasswordResetTest extends AuthIntegrationTest {

  private String requestCode(String email) {
    assertThat(http.post("/api/v1/auth/password-reset/request", Map.of("email", email)).status())
        .isEqualTo(202);
    var sent =
        mailer.await(email, StaffMailer.Template.PASSWORD_RESET_CODE, Duration.ofSeconds(30));
    assertThat(sent).as("OTP email within 30 seconds").isPresent();
    String code = sent.get().message().secret();
    assertThat(code).matches("^[0-9]{6}$");
    return code;
  }

  private Http.Response verify(String email, String code) {
    return http.post("/api/v1/auth/password-reset/verify", Map.of("email", email, "code", code));
  }

  @Test
  @Tag("FR-07-09")
  void codeExchangesForSingleUseResetTokenThatEndsEverySession() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Session session = login(account.email());
    String code = requestCode(account.email());
    assertThat(
            (byte[])
                query(
                    "SELECT code_hash FROM fraudshield.password_reset_otps WHERE user_id = ?",
                    account.id()))
        .as("only a keyed hash is stored")
        .hasSize(32);

    Http.Response verified = verify(account.email(), code);
    assertThat(verified.status()).isEqualTo(200);
    String resetToken = verified.json().get("reset_token").asString();
    assertThat(verify(account.email(), code).status())
        .as("used code cannot be reused")
        .isEqualTo(422);

    Map<String, Object> complete =
        Map.of("reset_token", resetToken, "new_password", "R3set!Passw0rd");
    assertThat(http.post("/api/v1/auth/password-reset/complete", complete).status()).isEqualTo(204);
    assertThat(http.post("/api/v1/auth/password-reset/complete", complete).status())
        .as("reset token is single use")
        .isEqualTo(410);
    assertThat(http.get("/api/v1/auth/me", session.bearer()).status()).isEqualTo(401);
    assertThat(
            http.post(
                    "/api/v1/auth/login",
                    Map.of("email", account.email(), "password", "R3set!Passw0rd"))
                .status())
        .isEqualTo(200);
    assertThat(auditActions(account.id()))
        .contains(
            "AUTH/PASSWORD_RESET_REQUESTED",
            "AUTH/PASSWORD_RESET_CODE_VERIFIED",
            "AUTH/PASSWORD_RESET_COMPLETED");
  }

  @Test
  void expiredCodeIsRejected() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    String code = requestCode(account.email());
    superuser(
        "UPDATE fraudshield.password_reset_otps"
            + " SET created_at = created_at - interval '11 minutes',"
            + " expires_at = expires_at - interval '11 minutes' WHERE user_id = ?",
        account.id());
    Http.Response response = verify(account.email(), code);
    assertThat(response.status()).isEqualTo(422);
    assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("code");
  }

  @Test
  void fiveWrongAttemptsExhaustTheCode() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    String code = requestCode(account.email());
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(verify(account.email(), wrong).status()).isEqualTo(422);
    }
    assertThat(verify(account.email(), code).status())
        .as("even the right code, after 5 attempts")
        .isEqualTo(422);
  }

  @Test
  void newCodeCancelsTheOldOne() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    String first = requestCode(account.email());
    clock.advance(Duration.ofSeconds(1));
    String second = requestCode(account.email());
    if (!first.equals(second)) {
      assertThat(verify(account.email(), first).status()).isEqualTo(422);
    }
    assertThat(verify(account.email(), second).status()).isEqualTo(200);
  }

  @Test
  void unknownOrInactiveEmailGetsTheSame202AndNoEmail() {
    String unknown = uniqueEmail("unknown");
    assertThat(http.post("/api/v1/auth/password-reset/request", Map.of("email", unknown)).status())
        .isEqualTo(202);
    Account deactivated = createAccount(BANK_A, "ANALYST", "DEACTIVATED");
    assertThat(
            http.post("/api/v1/auth/password-reset/request", Map.of("email", deactivated.email()))
                .status())
        .isEqualTo(202);
    assertThat(
            mailer.await(
                deactivated.email(),
                StaffMailer.Template.PASSWORD_RESET_CODE,
                Duration.ofMillis(500)))
        .isEmpty();
    assertThat(verify(unknown, "123456").status()).isEqualTo(422);
  }

  @Test
  void resetRequestsPerEmailAreRateLimited() {
    Account account = createAccount(BANK_A, "ANALYST", "ACTIVE");
    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(
              http.post("/api/v1/auth/password-reset/request", Map.of("email", account.email()))
                  .status())
          .isEqualTo(202);
    }
    Http.Response fourth =
        http.post("/api/v1/auth/password-reset/request", Map.of("email", account.email()));
    assertThat(fourth.status()).isEqualTo(429);
    assertThat(fourth.header("Retry-After")).isPresent();
  }
}
