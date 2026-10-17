package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an institution receives {@code decision.final} webhooks and the secrets to sign them with.
 * Implemented by the API-key module (M7), which stores the URL with the key and the signing secret
 * encrypted (V2 {@code api_keys.webhook_url}, {@code webhook_secret_ciphertext}).
 */
@FunctionalInterface
public interface WebhookEndpoints {

  /**
   * An institution's endpoint.
   *
   * @param url HTTPS URL
   * @param secrets every active signing secret; two during a key's 24-hour rotation overlap
   */
  record Endpoint(URI url, List<String> secrets) {
    /** Requires a URL and at least one secret. */
    public Endpoint {
      Objects.requireNonNull(url, "url");
      secrets = List.copyOf(secrets);
      if (secrets.isEmpty()) {
        throw new IllegalArgumentException("an endpoint needs a signing secret");
      }
    }

    @Override
    public String toString() {
      return "Endpoint[url=" + url + ", secrets=<" + secrets.size() + " redacted>]";
    }
  }

  /**
   * The institution's endpoint.
   *
   * @param institutionId institution
   * @return the endpoint, or empty when the institution registered none
   */
  Optional<Endpoint> find(UUID institutionId);
}
