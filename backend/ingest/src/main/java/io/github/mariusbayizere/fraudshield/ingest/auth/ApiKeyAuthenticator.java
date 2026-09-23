package io.github.mariusbayizere.fraudshield.ingest.auth;

import java.util.Optional;

/**
 * Verifies an {@code X-API-Key} header (FR-01-05, D-19). The contract between the ingestion API
 * (M6) and the API-key module (M7), which implements it: {@code fsk_<env>_<keyId>_<secret>}, {@code
 * HMAC-SHA256(pepper, secret)} compared in constant time against the record found by key id, cached
 * briefly, with revoked keys refused within 5 seconds (FR-06-07). No implementation of this
 * interface is shipped by M6; the application refuses to start without one.
 */
@FunctionalInterface
public interface ApiKeyAuthenticator {

  /**
   * Authenticates a raw key.
   *
   * @param rawKey the header value, possibly null or malformed
   * @return the principal, or empty for a missing, malformed, unknown, expired or revoked key
   */
  Optional<ApiPrincipal> authenticate(String rawKey);
}
