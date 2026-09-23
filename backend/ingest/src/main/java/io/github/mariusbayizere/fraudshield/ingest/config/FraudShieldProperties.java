package io.github.mariusbayizere.fraudshield.ingest.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API configuration under {@code fraudshield.*}, validated when the application starts (H.1: fail
 * fast on invalid configuration). Secrets come from the environment, never from committed files.
 *
 * @param instanceId this instance, for leader election
 * @param redisUri Redis URI
 * @param redisTimeout bound on each Redis call before falling back
 * @param kafkaBootstrapServers Kafka bootstrap servers
 * @param spoolDirectory local directory of the durable spool (D-15)
 * @param spoolMaxBytes spool bound
 * @param scorer the ML scorer connection
 * @param reviewWindow MEDIUM hold length (30 s)
 * @param anomalyReviewThreshold ANOMALY_REVIEW percentile (0.995; 0.7 in the test profile, D-06)
 * @param auditWriterPartition this instance's audit chain partition (0–63, D-32)
 * @param defaultLocale SMS locale until the vault supplies the customer's
 * @param idempotencyLease how long an unfinished claim blocks duplicates
 * @param duplicateWait how long a duplicate waits for the first submission
 * @param batchThreads threads deciding batch jobs
 * @param sms SMS provider, when customer SMS is enabled
 * @param vault the PII vault, when customer SMS is enabled (D-20)
 * @param rateLimit per-API-key request budget (E.1)
 * @param institutions per-institution SMS settings by institution id
 */
@ConfigurationProperties(prefix = "fraudshield")
public record FraudShieldProperties(
    String instanceId,
    String redisUri,
    Duration redisTimeout,
    String kafkaBootstrapServers,
    String spoolDirectory,
    long spoolMaxBytes,
    Scorer scorer,
    Duration reviewWindow,
    double anomalyReviewThreshold,
    int auditWriterPartition,
    String defaultLocale,
    Duration idempotencyLease,
    Duration duplicateWait,
    int batchThreads,
    Sms sms,
    Vault vault,
    RateLimit rateLimit,
    Map<String, Institution> institutions) {

  /**
   * The scorer connection (D-16).
   *
   * @param target host:port
   * @param plaintext only for local development
   * @param trustCertificate CA certificate resource location
   * @param clientCertificate client certificate resource location
   * @param clientKey client key resource location
   * @param deadline per-call deadline
   */
  public record Scorer(
      String target,
      boolean plaintext,
      String trustCertificate,
      String clientCertificate,
      String clientKey,
      Duration deadline) {}

  /**
   * Africa's Talking settings (D-51).
   *
   * @param baseUrl provider or sandbox base URL, or the local WireMock stub
   * @param username application username
   * @param apiKey application API key, from the environment
   */
  public record Sms(String baseUrl, String username, String apiKey) {
    @Override
    public String toString() {
      return "Sms[baseUrl=" + baseUrl + ", username=" + username + ", apiKey=<redacted>]";
    }
  }

  /**
   * The PII vault instance (D-20): its own PostgreSQL server, its own role, its own key material.
   *
   * @param url JDBC URL of the vault instance
   * @param username the {@code fs_vault} role
   * @param password its password, from the environment
   * @param keyProvider {@code kms} (the default: a key management service holds the key-encryption
   *     key, bound by the deployment as a {@code KmsClient} bean) or {@code configured} (the keys
   *     below, from the environment; development, demo and tests only, ADR 0069 point 10)
   * @param currentKeyId id of the key new rows are wrapped with: a master key below, or the key
   *     service's key-encryption key id
   * @param masterKeys {@code configured} only: master keys by id, each 32 bytes as base64; older
   *     ids stay here so rows written under them can still be read
   * @param readableKeyIds {@code kms} only: older key-encryption key ids whose rows may still be
   *     read; the current id is always readable
   * @param indexKey the tokenisation map's blind-index key, 32 bytes as base64 ({@code
   *     configured}), or that key as wrapped by the key service ({@code kms}); it never changes
   * @param indexKeyId {@code kms} only: the key-encryption key that wrapped the index key
   */
  public record Vault(
      String url,
      String username,
      String password,
      String keyProvider,
      String currentKeyId,
      Map<String, String> masterKeys,
      List<String> readableKeyIds,
      String indexKey,
      String indexKeyId) {

    /** Key material held in configuration. */
    public static final String CONFIGURED = "configured";

    /** Key material held by a key management service. */
    public static final String KMS = "kms";

    /** Validates a configured vault and copies its key material. */
    public Vault {
      if (url == null || username == null || password == null) {
        throw new IllegalArgumentException(
            "fraudshield.vault.url, username and password are required when the vault is set");
      }
      keyProvider = keyProvider == null ? KMS : keyProvider;
      masterKeys = masterKeys == null ? Map.of() : Map.copyOf(masterKeys);
      readableKeyIds = readableKeyIds == null ? List.of() : List.copyOf(readableKeyIds);
      if (currentKeyId == null || indexKey == null) {
        throw new IllegalArgumentException(
            "fraudshield.vault.current-key-id and index-key are required when the vault is set");
      }
      switch (keyProvider) {
        case CONFIGURED -> {
          if (masterKeys.isEmpty()) {
            throw new IllegalArgumentException(
                "fraudshield.vault.master-keys are required when the key provider is configured");
          }
        }
        case KMS -> {
          if (!masterKeys.isEmpty()) {
            // Key material in configuration next to a key service defeats the key service.
            throw new IllegalArgumentException(
                "fraudshield.vault.master-keys must be empty when the key provider is kms");
          }
          if (indexKeyId == null) {
            throw new IllegalArgumentException(
                "fraudshield.vault.index-key-id is required when the key provider is kms");
          }
        }
        default ->
            throw new IllegalArgumentException(
                "fraudshield.vault.key-provider is kms or configured, not " + keyProvider);
      }
    }

    @Override
    public String toString() {
      return "Vault[url="
          + url
          + ", username="
          + username
          + ", keyProvider="
          + keyProvider
          + ", keys=<redacted>]";
    }
  }

  /**
   * The per-API-key request budget (E.1).
   *
   * @param requestsPerSecond sustained requests per second per key
   * @param burst how many may arrive at once
   */
  public record RateLimit(int requestsPerSecond, int burst) {

    /** The default budget, used when none is configured. */
    public static final RateLimit DEFAULT = new RateLimit(200, 400);

    /** Validates the budget. */
    public RateLimit {
      if (requestsPerSecond < 1 || burst < requestsPerSecond) {
        throw new IllegalArgumentException(
            "fraudshield.rate-limit.requests-per-second must be at least 1 and burst at least"
                + " requests-per-second");
      }
    }
  }

  /**
   * One institution's customer-messaging settings (D-25).
   *
   * @param senderId registered sender ID
   * @param officialPhone official number
   * @param verificationBase {@code https://<domain>/v/}
   */
  public record Institution(String senderId, String officialPhone, String verificationBase) {}

  /** Validates at startup. */
  public FraudShieldProperties {
    require(instanceId, "fraudshield.instance-id");
    require(redisUri, "fraudshield.redis-uri");
    require(kafkaBootstrapServers, "fraudshield.kafka-bootstrap-servers");
    require(spoolDirectory, "fraudshield.spool-directory");
    if (scorer == null || scorer.target() == null || scorer.deadline() == null) {
      throw new IllegalArgumentException("fraudshield.scorer.target and deadline are required");
    }
    if (auditWriterPartition < 0 || auditWriterPartition > 63) {
      throw new IllegalArgumentException("fraudshield.audit-writer-partition is 0 to 63");
    }
    if (batchThreads < 1 || spoolMaxBytes < 1) {
      throw new IllegalArgumentException("batch threads and the spool bound must be positive");
    }
    institutions = institutions == null ? Map.of() : Map.copyOf(institutions);
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
