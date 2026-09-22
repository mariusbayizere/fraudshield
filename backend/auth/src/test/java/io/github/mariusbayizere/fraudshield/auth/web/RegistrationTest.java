package io.github.mariusbayizere.fraudshield.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Self-registration, availability and email verification (FR-07-02, D-24, E.8). */
@Tag("FR-07-02")
class RegistrationTest extends AuthIntegrationTest {

  private static Map<String, Object> registration(
      String email, String employeeId, String password) {
    Map<String, Object> body = new HashMap<>();
    body.put("first_name", "Jean-Paul");
    body.put("last_name", "Niyonzima");
    body.put("email", email);
    body.put("phone", "+250788000001");
    body.put("employee_id", employeeId);
    body.put("department", "FRAUD_OPERATIONS");
    body.put("requested_role", "RISK_OFFICER");
    body.put("password", password);
    body.put("preferred_locale", "rw");
    return body;
  }

  @Test
  @Tag("D-24")
  void registrationCreatesPendingAccountWithRequestedRoleButNoGrantedRole() {
    String email = uniqueEmail("register");
    Http.Response response =
        http.post("/api/v1/auth/register", registration(email, uniqueEmployeeId(), PASSWORD));
    assertThat(response.status()).isEqualTo(202);
    assertThat(response.json().get("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(query("SELECT status FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo("PENDING_APPROVAL");
    assertThat(query("SELECT requested_role FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo("RISK_OFFICER");
    assertThat(query("SELECT role FROM fraudshield.users WHERE email = ?", email))
        .as("the granted role is a placeholder with no access until an administrator decides")
        .isEqualTo("ANALYST");
    assertThat(query("SELECT department FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo("FRAUD_OPERATIONS");
    assertThat((String) query("SELECT password_hash FROM fraudshield.users WHERE email = ?", email))
        .startsWith("$2a$12$");

    Http.Response signIn =
        http.post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(signIn.status()).isEqualTo(403);
    assertThat(signIn.problemType()).isEqualTo("urn:fraudshield:problem:account-not-active");

    var verification =
        mailer.await(email, StaffMailer.Template.EMAIL_VERIFICATION, Duration.ofSeconds(10));
    assertThat(verification).isPresent();
    String link = verification.get().message().secret();
    String token = link.substring(link.indexOf("#token=") + 7);
    assertThat(http.post("/api/v1/auth/email-verification", Map.of("token", token)).status())
        .isEqualTo(204);
    assertThat(query("SELECT email_verified FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo(true);
    assertThat(http.post("/api/v1/auth/email-verification", Map.of("token", token)).status())
        .isEqualTo(410);
  }

  @Test
  void duplicateDetailsGetTheSame202AndTheOwnerIsEmailed() {
    Account existing = createAccount(BANK_A, "ANALYST", "ACTIVE");
    Http.Response response =
        http.post(
            "/api/v1/auth/register", registration(existing.email(), uniqueEmployeeId(), PASSWORD));
    assertThat(response.status()).isEqualTo(202);
    assertThat(response.json().get("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(
            mailer.await(
                existing.email(),
                StaffMailer.Template.REGISTRATION_EXISTING_ACCOUNT,
                Duration.ofSeconds(10)))
        .isPresent();
    assertThat(query("SELECT count(*) FROM fraudshield.users WHERE email = ?", existing.email()))
        .isEqualTo(1L);
  }

  @Test
  void domainOutsideTheAllowlistCreatesNothingAndSendsNothing() {
    String email = "outsider." + System.nanoTime() + "@elsewhere.example";
    assertThat(
            http.post("/api/v1/auth/register", registration(email, uniqueEmployeeId(), PASSWORD))
                .status())
        .isEqualTo(202);
    assertThat(query("SELECT count(*) FROM fraudshield.users WHERE email = ?", email))
        .isEqualTo(0L);
    assertThat(mailer.to(email)).isEmpty();
  }

  @Test
  @Tag("FR-07-07")
  void weakPasswordIsRejectedWithTheSpecificReason() {
    Http.Response response =
        http.post(
            "/api/v1/auth/register",
            registration(uniqueEmail("weak"), uniqueEmployeeId(), "nodigits!Here"));
    assertThat(response.status()).isEqualTo(422);
    var error = response.json().get("errors").get(0);
    assertThat(error.get("field").asString()).isEqualTo("password");
    assertThat(error.get("code").asString()).isEqualTo("password_policy");
    assertThat(error.get("message").asString()).startsWith("DIGIT");
  }

  @Test
  void invalidFieldsAreReportedTogetherWithContractCodes() {
    Map<String, Object> body = registration(uniqueEmail("bad"), "no", PASSWORD);
    body.put("first_name", "J4ne");
    body.put("department", "RISK_OFFICER");
    body.remove("phone");
    Http.Response response = http.post("/api/v1/auth/register", body);
    assertThat(response.status()).as("any 400-class error makes the response 400").isEqualTo(400);
    assertThat(
            response
                .json()
                .get("errors")
                .valueStream()
                .map(e -> e.get("field").asString() + ":" + e.get("code").asString()))
        .containsExactlyInAnyOrder(
            "phone:required",
            "first_name:person_name",
            "department:unsupported_value",
            "employee_id:invalid_format");
  }

  @Test
  void availabilityAnswersForEmailAndEmployeeId() {
    Account existing = createAccount(BANK_A, "ANALYST", "ACTIVE");
    String employeeId =
        (String) query("SELECT employee_id FROM fraudshield.users WHERE id = ?", existing.id());
    assertThat(
            http.get("/api/v1/auth/availability?field=email&value=" + existing.email())
                .json()
                .get("available")
                .asBoolean())
        .isFalse();
    assertThat(
            http.get(
                    "/api/v1/auth/availability?field=email&value=free."
                        + System.nanoTime()
                        + "@bank-a.example.rw")
                .json()
                .get("available")
                .asBoolean())
        .isTrue();
    assertThat(
            http.get("/api/v1/auth/availability?field=employee_id&value=" + employeeId)
                .json()
                .get("available")
                .asBoolean())
        .isFalse();
    assertThat(http.get("/api/v1/auth/availability?field=phone&value=x").status()).isEqualTo(422);
    assertThat(http.get("/api/v1/auth/availability?field=email").status()).isEqualTo(400);
  }
}
