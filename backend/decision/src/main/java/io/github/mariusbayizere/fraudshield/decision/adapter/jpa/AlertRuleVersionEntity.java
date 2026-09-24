package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One published version of a rule (V4 {@code alert_rule_versions}), append-only and therefore
 * {@link Immutable}. The expression is {@code jsonb}; the decision path compiles it with the rule
 * DSL, so it is read as text rather than mapped.
 */
@Entity
@Immutable
@Table(name = "alert_rule_versions")
@IdClass(AlertRuleVersionEntity.Key.class)
public class AlertRuleVersionEntity {

  /** The composite key: rule and version. */
  public static final class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID ruleId;
    private int version;

    /** For Hibernate. */
    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && version == key.version
          && Objects.equals(ruleId, key.ruleId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ruleId, version);
    }
  }

  @Id
  @Column(name = "rule_id", nullable = false)
  private UUID ruleId;

  @Id
  @Column(name = "version", nullable = false)
  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rule_expression", nullable = false)
  private String ruleExpression;

  @Column(name = "risk_tier_override", nullable = false)
  private String riskTierOverride;

  /** For Hibernate. */
  protected AlertRuleVersionEntity() {}

  /**
   * The rule this version belongs to.
   *
   * @return the rule id
   */
  public UUID ruleId() {
    return ruleId;
  }

  /**
   * The version number.
   *
   * @return the version
   */
  public int version() {
    return version;
  }

  /**
   * The rule expression as JSON text, for the DSL compiler.
   *
   * @return the expression
   */
  public String ruleExpression() {
    return ruleExpression;
  }

  /**
   * The tier this rule raises a decision to.
   *
   * @return the override
   */
  public String riskTierOverride() {
    return riskTierOverride;
  }
}
