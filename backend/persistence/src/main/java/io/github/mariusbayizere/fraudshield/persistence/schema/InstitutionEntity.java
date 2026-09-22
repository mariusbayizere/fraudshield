package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * An institution (V1 {@code institutions}), the tenant every other row belongs to.
 *
 * <p>Mutable CRUD, so JPA under ADR 0068. {@code synthetic} may be written only by {@code
 * fs_migrator} (ADR 0019), which is the role the demo seeder runs as; the application's {@code
 * fs_app} never writes this table.
 */
@Entity
@DynamicUpdate
@Table(name = "institutions")
public class InstitutionEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false)
  private String code;

  @Column(name = "name", nullable = false)
  private String name;

  // char(2) in V1, which Hibernate sees as bpchar: the mapping says so, or validate refuses.
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
  @Column(name = "country", nullable = false, length = 2)
  private String country;

  @Column(name = "synthetic", nullable = false)
  private boolean synthetic;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  /** For Hibernate. */
  protected InstitutionEntity() {}

  /**
   * A new institution.
   *
   * @param id the institution
   * @param code its short code
   * @param name its display name
   * @param country ISO 3166-1 alpha-2
   * @param synthetic true for demo and test data (ADR 0019)
   */
  public InstitutionEntity(UUID id, String code, String name, String country, boolean synthetic) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.country = country;
    this.synthetic = synthetic;
  }

  /**
   * The institution's id.
   *
   * @return the id
   */
  public UUID id() {
    return id;
  }

  /**
   * Its code.
   *
   * @return the code
   */
  public String code() {
    return code;
  }
}
