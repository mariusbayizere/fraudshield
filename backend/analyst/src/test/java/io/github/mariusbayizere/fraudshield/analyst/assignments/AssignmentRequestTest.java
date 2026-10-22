package io.github.mariusbayizere.fraudshield.analyst.assignments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code assignee_user_id} is required but nullable: an absent member and an explicit null must
 * bind differently, and the fingerprint must tell an assignment from an unassignment.
 */
@Tag("D-29")
class AssignmentRequestTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static AssignmentRequest read(String body) {
    return JSON.readValue(body, AssignmentRequest.class);
  }

  @Test
  void absentAssigneeIsJavaNullSoBeanValidationAnswersRequired() {
    AssignmentRequest request = read("{\"alert_version\": 3}");
    assertThat(request.assigneeUserId()).isNull();
  }

  @Test
  void explicitNullReturnsTheEntryToThePool() {
    AssignmentRequest request = read("{\"assignee_user_id\": null, \"alert_version\": 3}");
    assertThat(request.assigneeUserId()).isNotNull();
    assertThat(request.assignee()).isNull();
    assertThat(request.alertVersion()).isEqualTo(3L);
  }

  @Test
  void uuidIsParsed() {
    UUID id = UUID.randomUUID();
    assertThat(read("{\"assignee_user_id\": \"" + id + "\", \"alert_version\": 0}").assignee())
        .isEqualTo(id);
  }

  @Test
  void nonUuidIsInvalidFormatAndNonStringIsTypeMismatch() {
    assertThat(code("{\"assignee_user_id\": \"1-1-1-1-1\", \"alert_version\": 0}"))
        .isEqualTo("invalid_format");
    assertThat(code("{\"assignee_user_id\": 7, \"alert_version\": 0}")).isEqualTo("type_mismatch");
  }

  private static String code(String body) {
    AssignmentRequest request = read(body);
    try {
      request.assignee();
    } catch (ProblemException e) {
      assertThat(e.status()).isEqualTo(422);
      return e.errors().getFirst().code();
    }
    throw new AssertionError("accepted " + body);
  }

  @Test
  void fingerprintSeparatesAssignAndUnassign() {
    String alert = UUID.randomUUID().toString();
    assertThat(AlertAssignments.fingerprint("assign", alert, "", "1"))
        .isNotEqualTo(
            AlertAssignments.fingerprint("assign", alert, UUID.randomUUID().toString(), "1"))
        .isEqualTo(AlertAssignments.fingerprint("assign", alert, "", "1"));
  }
}
