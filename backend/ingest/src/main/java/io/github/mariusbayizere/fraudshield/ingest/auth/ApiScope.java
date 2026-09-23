package io.github.mariusbayizere.fraudshield.ingest.auth;

import java.util.Optional;

/** API-key scopes (V2 {@code api_keys.scopes}, OpenAPI {@code x-required-scopes}). */
public enum ApiScope {
  /** Submit transactions. */
  INGEST_WRITE("ingest:write"),
  /** Read decisions. */
  DECISIONS_READ("decisions:read"),
  /** Read batch jobs. */
  JOBS_READ("jobs:read");

  private final String wireName;

  ApiScope(String wireName) {
    this.wireName = wireName;
  }

  /**
   * The wire name.
   *
   * @return for example {@code ingest:write}
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Parses a wire name.
   *
   * @param wireName for example {@code decisions:read}
   * @return the scope, or empty
   */
  public static Optional<ApiScope> parse(String wireName) {
    for (ApiScope scope : values()) {
      if (scope.wireName.equals(wireName)) {
        return Optional.of(scope);
      }
    }
    return Optional.empty();
  }
}
