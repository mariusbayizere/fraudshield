package io.github.mariusbayizere.fraudshield.ingest.application;

import io.github.mariusbayizere.fraudshield.ingest.request.RequestValidator;
import io.github.mariusbayizere.fraudshield.ingest.request.ValidationError;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 9457 problem details with the contract's stable type URNs and a correlation id (ADR 0011
 * section 6). Details never echo request values, which may be personal data.
 */
public final class Problems {

  /** Problem media type. */
  public static final String MEDIA_TYPE = "application/problem+json";

  private static final ObjectMapper JSON = new ObjectMapper();

  private Problems() {}

  /**
   * A problem.
   *
   * @param type the name after {@code urn:fraudshield:problem:}
   * @param title short title
   * @param status HTTP status
   * @param detail detail, or null
   * @param correlationId correlation id
   * @return the problem
   */
  public static ObjectNode problem(
      String type, String title, int status, String detail, UUID correlationId) {
    ObjectNode node = JSON.createObjectNode();
    node.put("type", "urn:fraudshield:problem:" + type);
    node.put("title", title);
    node.put("status", status);
    if (detail != null) {
      node.put("detail", detail);
    }
    node.put("correlation_id", correlationId.toString());
    return node;
  }

  /**
   * A validation problem listing every error.
   *
   * @param result the validation result
   * @param correlationId correlation id
   * @return the problem
   */
  public static ObjectNode validation(RequestValidator.Result result, UUID correlationId) {
    int count = result.errors().size();
    ObjectNode node =
        problem(
            "validation",
            "Request validation failed",
            result.status(),
            count + (count == 1 ? " field is invalid" : " fields are invalid"),
            correlationId);
    ArrayNode errors = node.putArray("errors");
    for (ValidationError error : result.errors()) {
      errors
          .addObject()
          .put("field", error.field())
          .put("code", error.code())
          .put("message", error.message());
    }
    return node;
  }
}
