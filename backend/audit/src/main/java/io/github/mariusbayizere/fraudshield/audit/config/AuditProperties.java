package io.github.mariusbayizere.fraudshield.audit.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit configuration ({@code fraudshield.audit.*}).
 *
 * @param writerPartition a fixed chain partition, 0-63; unset (the default) spreads writes over all
 *     64 partitions by writing thread, keeping concurrent transactions off one chain head
 * @param transactionAttempts attempts for a tenant transaction refused by the chain with a
 *     serialization failure (default 5)
 * @param anchor daily anchoring job
 */
@ConfigurationProperties("fraudshield.audit")
public record AuditProperties(Short writerPartition, Integer transactionAttempts, Anchor anchor) {

  private static final int DEFAULT_ATTEMPTS = 5;

  /** Applies defaults. */
  public AuditProperties {
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
