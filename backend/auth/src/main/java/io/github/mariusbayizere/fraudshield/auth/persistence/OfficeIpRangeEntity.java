package io.github.mariusbayizere.fraudshield.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * JPA mapping of {@code office_ip_allowlist} (ADR 0071). {@code cidr} is a PostgreSQL {@code cidr};
 * it is written through a cast and read as text by the driver. The creator is a lazy association
 * fetched by entity graph in the list, so listing N ranges is one query, not N + 1.
 */
@Entity
@Table(name = "office_ip_allowlist")
public class OfficeIpRangeEntity {

  @Id private UUID id;

  @Column(name = "institution_id", nullable = false, updatable = false)
  private UUID institutionId;

  @Column(nullable = false, updatable = false, columnDefinition = "cidr")
  @ColumnTransformer(write = "?::cidr")
  private String cidr;

  @Column(nullable = false)
  private String description;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "created_by", nullable = false, updatable = false)
  private StaffUserEntity createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** For JPA. */
  protected OfficeIpRangeEntity() {}

  /**
   * A new range.
   *
   * @param id entry ID
   * @param institutionId institution
   * @param cidr canonical CIDR
   * @param description description
   * @param createdBy administrator (a reference is enough)
   * @param createdAt creation time
   * @return the entity to persist
   */
  public static OfficeIpRangeEntity create(
      UUID id,
      UUID institutionId,
      String cidr,
      String description,
      StaffUserEntity createdBy,
      Instant createdAt) {
    OfficeIpRangeEntity entity = new OfficeIpRangeEntity();
    entity.id = id;
    entity.institutionId = institutionId;
    entity.cidr = cidr;
    entity.description = description;
    entity.createdBy = createdBy;
    entity.createdAt = createdAt;
    return entity;
  }

  public UUID getId() {
    return id;
  }

  public String getCidr() {
    return cidr;
  }

  public String getDescription() {
    return description;
  }

  public StaffUserEntity getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
