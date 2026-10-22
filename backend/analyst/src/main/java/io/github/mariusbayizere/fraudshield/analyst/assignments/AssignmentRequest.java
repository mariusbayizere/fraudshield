package io.github.mariusbayizere.fraudshield.analyst.assignments;

import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The contract's {@code AlertAssignmentRequest}. {@code assignee_user_id} is required but may be
 * null, so it is bound as a JSON node: an absent member is Java null (400 {@code required}), and an
 * explicit JSON null is a null node, which returns the entry to the pool.
 *
 * @param assigneeUserId the user to assign, JSON null for the pool
 * @param alertVersion the version the caller saw
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssignmentRequest(
    @NotNull JsonNode assigneeUserId, @NotNull @Min(0) Long alertVersion) {

  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * The assignee.
   *
   * @return the user to assign, or null to return the entry to the pool
   * @throws ProblemException 422 when the member is not a UUID string
   */
  public UUID assignee() {
    if (assigneeUserId == null || assigneeUserId.isNull()) {
      return null;
    }
    if (!assigneeUserId.isString()) {
      throw ProblemException.validation("assignee_user_id", "type_mismatch", "Wrong JSON type");
    }
    String text = assigneeUserId.asString();
    if (!UUID_TEXT.matcher(text).matches()) {
      throw ProblemException.validation("assignee_user_id", "invalid_format", "Not a UUID");
    }
    return UUID.fromString(text);
  }
}
