package io.github.mariusbayizere.fraudshield.audit.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit configuration ({@code fraudshield.audit.*}).
 *
 * @param writerPartition chain partition of this instance, 0-63 (default 0); concurrently running
 *     instances should differ to avoid contention on one chain head
 * @param transactionAttempts attempts for a tenant transaction refused by the chain with a
 *     serialization failure (default 5)
 * @param anchor daily anchoring job
 */
@ConfigurationProperties("fraudshield.audit")
public record AuditProperties(Short writerPartition, Integer transactionAttempts, Anchor anchor) {

  private static final short DEFAULT_PARTITION = 0;
  private static final int DEFAULT_ATTEMPTS = 5;

  /** Applies defaults. */
  public AuditProperties {
    writerPartition = Objects.requireNonNullElse(writerPartition, DEFAULT_PARTITION);
    transactionAttempts = Objects.requireNonNullElse(transactionAttempts, DEFAULT_ATTEMPTS);
    anchor = anchor == null ? new Anchor(false, null, null, null) : anchor;
  }

  /**
   * Daily anchoring job (D-32).
   *
   * @param enabled whether this instance runs the job
   * @param signingKey Ed25519 PKCS#8 PEM private key
   * @param signingKeyId identifier stored with each anchor
   * @param cron schedule in UTC (default 00:10 every day)
   */
  public record Anchor(boolean enabled, Path signingKey, String signingKeyId, String cron) {}
}
