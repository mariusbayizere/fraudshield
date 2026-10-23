package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One version of an institution's thresholds (V6 {@code risk_threshold_versions}), append-only and
 * therefore {@link Immutable}. It carries the time the version takes effect, which is what decides
 * the version in force.
 */
@Entity(name = "SeedRiskThresholdVersion")
@Immutable
@Table(name = "risk_threshold_versions")
@IdClass(RiskThresholdVersionEntity.Key.class)
public class RiskThresholdVersionEntity {

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

  @Column(name = "effective_at", nullable = false)
  private Instant effectiveAt;

  /** For Hibernate. */
  protected RiskThresholdVersionEntity() {}

  /**
   * A new version. Append-only: a version is written once and never changed.
   *
   * @param institutionId institution
   * @param version the version number
   * @param effectiveAt when it takes effect
   */
  public RiskThresholdVersionEntity(UUID institutionId, long version, Instant effectiveAt) {
    this.institutionId = institutionId;
    this.version = version;
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
   * When it takes effect.
   *
   * @return the time
   */
  public Instant effectiveAt() {
    return effectiveAt;
  }
}
