package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One version of an institution's MCC circuit-breaker settings (V6 {@code
 * mcc_circuit_breaker_settings_versions}), append-only and therefore {@link Immutable}.
 */
@Entity(name = "SeedBreakerSettings")
@Immutable
@Table(name = "mcc_circuit_breaker_settings_versions")
@IdClass(BreakerSettingsEntity.Key.class)
public class BreakerSettingsEntity {

  /** The composite key: institution and version. */
  public static final class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID institutionId;
    private long version;

    /** For Hibernate. */
    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && version == key.version
          && Objects.equals(institutionId, key.institutionId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(institutionId, version);
    }
  }

  @Id
  @Column(name = "institution_id", nullable = false)
  private UUID institutionId;

  @Id
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "fraud_rate_threshold", nullable = false)
  private BigDecimal fraudRateThreshold;

  @Column(name = "window_minutes", nullable = false)
  private int windowMinutes;

  @Column(name = "minimum_transactions", nullable = false)
  private int minimumTransactions;

  @Column(name = "clean_reset_minutes", nullable = false)
  private int cleanResetMinutes;

  @Column(name = "effective_at", nullable = false)
  private Instant effectiveAt;

  /** For Hibernate. */
  protected BreakerSettingsEntity() {}

  /**
   * A new version of the settings. Append-only: written once, never changed.
   *
   * @param institutionId institution
   * @param version the version number
   * @param fraudRateThreshold the rate the breaker opens above
   * @param windowMinutes the window
   * @param minimumTransactions the minimum volume
   * @param cleanResetMinutes how long a clean window closes it after
   * @param effectiveAt when it takes effect
   */
  public BreakerSettingsEntity(
      UUID institutionId,
      long version,
      BigDecimal fraudRateThreshold,
      int windowMinutes,
      int minimumTransactions,
      int cleanResetMinutes,
      Instant effectiveAt) {
    this.institutionId = institutionId;
    this.version = version;
    this.fraudRateThreshold = fraudRateThreshold;
    this.windowMinutes = windowMinutes;
    this.minimumTransactions = minimumTransactions;
    this.cleanResetMinutes = cleanResetMinutes;
    this.effectiveAt = effectiveAt;
  }

  /**
   * The version.
   *
   * @return the version
   */
  public long version() {
    return version;
  }

  /**
   * The fraud-rate threshold the breaker opens above.
   *
   * @return the rate
   */
  public BigDecimal fraudRateThreshold() {
    return fraudRateThreshold;
  }

  /**
   * The window.
   *
   * @return minutes
   */
  public int windowMinutes() {
    return windowMinutes;
  }

  /**
   * The minimum volume before the rate means anything.
   *
   * @return transactions
   */
  public int minimumTransactions() {
    return minimumTransactions;
  }

  /**
   * How long a clean window closes the breaker after.
   *
   * @return minutes
   */
  public int cleanResetMinutes() {
    return cleanResetMinutes;
  }
}
