package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import io.github.mariusbayizere.fraudshield.persistence.schema.AlertRuleEntity;
import io.github.mariusbayizere.fraudshield.persistence.schema.AlertRuleVersionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * The custom rules in force (ADR 0068). One statement joins each enabled rule to its current
 * version, so a rule set of any size costs one query and navigates no association.
 */
public interface RuleRepository extends Repository<AlertRuleEntity, UUID> {

  /** A rule with the version in force. */
  interface Published {

    /**
     * The rule header.
     *
     * @return the rule
     */
    AlertRuleEntity getRule();

    /**
     * The version in force.
     *
     * @return the version
     */
    AlertRuleVersionEntity getPublished();
  }

  /**
   * Every enabled rule with its current version, in one query.
   *
   * @return rule and version pairs, ordered by rule id
   */
  @Query(
      """
      select r as rule, v as published from AlertRuleEntity r join AlertRuleVersionEntity v
        on v.ruleId = r.id and v.version = r.currentVersion
      where r.state = 'ENABLED' order by r.id
      """)
  List<Published> enabledWithCurrentVersion();
}
