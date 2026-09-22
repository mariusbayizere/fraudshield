package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A custom rule's header (V4 {@code alert_rules}). The table is mutable — an administrator enables,
 * disables and republishes rules (M7's admin API) — so this entity is an ordinary one, with the
 * {@code version} column as the optimistic lock that administration will use. M6 only reads it.
 */
@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @jakarta.persistence.Version
  @Column(name = "version", nullable = false)
  private long version;

  /** For Hibernate. */
  protected AlertRuleEntity() {}

  /**
   * The rule's id.
   *
   * @return the id
   */
  public UUID id() {
    return id;
  }

  /**
   * Which version of this rule is in force.
   *
   * @return the version number
   */
  public int currentVersion() {
    return currentVersion;
  }

  /**
   * The optimistic-lock version, which the decision path reports as the rule set's version.
   *
   * @return the version
   */
  public long version() {
    return version;
  }

  /**
   * Whether the rule is enabled.
   *
   * @return the state
   */
  public String state() {
    return state;
  }
}
