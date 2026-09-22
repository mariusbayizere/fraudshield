package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One channel's thresholds in one version (V6 {@code risk_thresholds}).
 *
 * <p>{@link Immutable}: the table is append-only, so Hibernate must never write an UPDATE for it. A
 * new configuration is a new version, never an edit (ADR 0068, ADR 0071 point 2).
 */
@Entity
@Immutable
@Table(name = "risk_thresholds")
@IdClass(RiskThresholdEntity.Key.class)
public class RiskThresholdEntity {

  /** The composite key: institution, version and channel. */
  public static final class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID institutionId;
    private long version;
    private String channel;

    /** For Hibernate. */
    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && version == key.version
          && Objects.equals(institutionId, key.institutionId)
          && Objects.equals(channel, key.channel);
    }

    @Override
    public int hashCode() {
      return Objects.hash(institutionId, version, channel);
    }
  }

  @Id
  @Column(name = "institution_id", nullable = false)
  private UUID institutionId;

  @Id
  @Column(name = "version", nullable = false)
  private long version;

  @Id
  @Column(name = "channel", nullable = false)
  private String channel;

  @Column(name = "medium_threshold", nullable = false)
  private BigDecimal mediumThreshold;

  @Column(name = "high_threshold", nullable = false)
  private BigDecimal highThreshold;

  @Column(name = "medium_timeout_policy", nullable = false)
  private String mediumTimeoutPolicy;

  /** For Hibernate. */
  protected RiskThresholdEntity() {}

  /**
   * The version this row belongs to.
   *
   * @return the version
   */
  public long version() {
    return version;
  }

  /**
   * The channel.
   *
   * @return the channel name
   */
  public String channel() {
    return channel;
  }

  /**
   * The MEDIUM threshold.
   *
   * @return the threshold
   */
  public BigDecimal mediumThreshold() {
    return mediumThreshold;
  }

  /**
   * The HIGH threshold.
   *
   * @return the threshold
   */
  public BigDecimal highThreshold() {
    return highThreshold;
  }

  /**
   * What happens when a MEDIUM hold times out.
   *
   * @return the policy name
   */
  public String mediumTimeoutPolicy() {
    return mediumTimeoutPolicy;
  }
}
