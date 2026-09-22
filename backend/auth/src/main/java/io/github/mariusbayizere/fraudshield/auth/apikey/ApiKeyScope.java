package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.util.Arrays;
import java.util.Optional;

/** API-key scopes (E.8): machine operations only, never staff endpoints (FR-07-05). */
public enum ApiKeyScope {
  /** Transaction ingestion, single and batch. */
  INGEST_WRITE("ingest:write"),
  /** Final decisions of held transactions. */
  DECISIONS_READ("decisions:read"),
  /** Batch job status. */
  JOBS_READ("jobs:read");

  private final String value;

  ApiKeyScope(String value) {
    this.value = value;
  }

  /**
   * The scope as written in the contract and the database.
   *
   * @return the value
   */
  public String value() {
    return value;
  }

  /**
   * Parses a contract value.
   *
   * @param value the value
   * @return the scope, or empty if unknown
   */
  public static Optional<ApiKeyScope> parse(String value) {
    return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
  }
}
