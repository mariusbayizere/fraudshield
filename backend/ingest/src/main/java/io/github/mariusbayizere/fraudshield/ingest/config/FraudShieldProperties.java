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
 * @param rateLimit per-API-key budget in transactions (E.1, ADR 0058)
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
   * The per-API-key budget (E.1), counted in transactions (ADR 0058, adopting ADR 0100): a single
   * submission costs one unit, a batch its item count.
   *
   * @param transactionsPerSecond sustained transactions per second per key
   * @param burst how many transactions may arrive at once; at least the largest batch, or a
   *     full-size batch could never be accepted
   */
  public record RateLimit(int transactionsPerSecond, int burst) {

    /**
     * The default budget: 2,000 transactions per second sustained, burst 4,000, per key.
     * **ASSUMED** (ADR 0100, adopted by the owner on 2026-09-24 in ADR 0058): one fifth of the
     * system's specified 10,000 per second, with two seconds of burst. Not sourced and not
     * measured.
     */
    public static final RateLimit DEFAULT = new RateLimit(2_000, 4_000);

    /** Validates the budget. */
    public RateLimit {
      if (transactionsPerSecond < 1 || burst < transactionsPerSecond) {
        throw new IllegalArgumentException(
            "fraudshield.rate-limit.transactions-per-second must be at least 1 and burst at least"
                + " transactions-per-second");
      }
      if (burst < io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs.MAX_ITEMS) {
        throw new IllegalArgumentException(
            "fraudshield.rate-limit.burst must be at least "
                + io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs.MAX_ITEMS
                + ", the largest batch: a batch is charged whole, so a smaller burst would refuse a"
                + " full batch for ever");
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
