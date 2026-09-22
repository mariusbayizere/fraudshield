package io.github.mariusbayizere.fraudshield.auth.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A request failure answered with an RFC 9457 problem (ADR 0011). The {@code type} is always one of
 * the contract's problem-type URNs; the handler adds the correlation ID.
 */
public final class ProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;
  private static final String PREFIX = "urn:fraudshield:problem:";

  private final String type;
  private final int status;
  private final String title;
  private final transient List<FieldProblem> errors;
  private final transient Map<String, Object> extensions;
  private final Long retryAfterSeconds;

  private ProblemException(
      String type,
      int status,
      String title,
      String detail,
      List<FieldProblem> errors,
      Map<String, Object> extensions,
      Long retryAfterSeconds) {
    super(detail == null ? title : detail);
    this.type = PREFIX + Objects.requireNonNull(type, "type");
    this.status = status;
    this.title = Objects.requireNonNull(title, "title");
    this.errors = List.copyOf(errors);
    this.extensions = Map.copyOf(extensions);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * A problem without field errors.
   *
   * @param type problem-type slug, for example {@code conflict}
   * @param status HTTP status
   * @param title short title
   * @param detail detail, or null
   * @return the exception
   */
  public static ProblemException of(String type, int status, String title, String detail) {
    return new ProblemException(type, status, title, detail, List.of(), Map.of(), null);
  }

  /**
   * A validation problem; the status follows the codes (400 if any is a 400-class code).
   *
   * @param errors field errors, at least one
   * @return the exception
   */
  public static ProblemException validation(List<FieldProblem> errors) {
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("a validation problem has at least one error");
    }
    int status = errors.stream().anyMatch(e -> e.status() == 400) ? 400 : 422;
    String detail =
        errors.size() == 1 ? "1 field is invalid" : errors.size() + " fields are invalid";
    return new ProblemException(
        "validation", status, "Request validation failed", detail, errors, Map.of(), null);
  }

  /**
   * One field error.
   *
   * @param field field
   * @param code validation code
   * @param message message
   * @return the exception
   */
  public static ProblemException validation(String field, String code, String message) {
    return validation(List.of(new FieldProblem(field, code, message)));
  }

  /**
   * 401: missing, invalid, expired or revoked credentials. Deliberately says nothing more.
   *
   * @return the exception
   */
  public static ProblemException unauthorized() {
    return of("unauthorized", 401, "Authentication required", "Invalid or expired credentials");
  }

  /**
   * 403: authenticated but not permitted.
   *
   * @return the exception
   */
  public static ProblemException forbidden() {
    return of("forbidden", 403, "Forbidden", "You do not have permission for this operation");
  }

  /**
   * 404 within the caller's institution.
   *
   * @param what what was not found
   * @return the exception
   */
  public static ProblemException notFound(String what) {
    return of("not-found", 404, "Not found", what + " was not found");
  }

  /**
   * 429 with Retry-After.
   *
   * @param retryAfterSeconds seconds to wait, at least 1
   * @return the exception
   */
  public static ProblemException rateLimited(long retryAfterSeconds) {
    return new ProblemException(
        "rate-limited",
        429,
        "Too many requests",
        "Too many attempts; try again later",
        List.of(),
        Map.of(),
        Math.max(1, retryAfterSeconds));
  }

  /**
   * Adds an extension member (for example the current account on a stale-version conflict).
   *
   * @param name member name
   * @param value member value
   * @return a new exception with the member
   */
  public ProblemException with(String name, Object value) {
    Map<String, Object> more = new LinkedHashMap<>(extensions);
    more.put(name, value);
    return new ProblemException(
        type.substring(PREFIX.length()),
        status,
        title,
        getMessage(),
        errors,
        more,
        retryAfterSeconds);
  }

  /**
   * Adds a Retry-After (for 503).
   *
   * @param seconds seconds to wait, at least 1
   * @return a new exception with the header
   */
  public ProblemException withRetryAfter(long seconds) {
    return new ProblemException(
        type.substring(PREFIX.length()),
        status,
        title,
        getMessage(),
        errors,
        extensions,
        Math.max(1, seconds));
  }

  /**
   * Problem-type URN.
   *
   * @return the type
   */
  public String type() {
    return type;
  }

  /**
   * HTTP status.
   *
   * @return the status
   */
  public int status() {
    return status;
  }

  /**
   * Title.
   *
   * @return the title
   */
  public String title() {
    return title;
  }

  /**
   * Field errors.
   *
   * @return the errors, empty unless a validation problem
   */
  public List<FieldProblem> errors() {
    return errors;
  }

  /**
   * Extension members.
   *
   * @return the members
   */
  public Map<String, Object> extensions() {
    return extensions;
  }

  /**
   * Retry-After seconds, for 429 and 503.
   *
   * @return seconds, or null
   */
  public Long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
