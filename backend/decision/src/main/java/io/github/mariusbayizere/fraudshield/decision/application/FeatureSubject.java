package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSubject;
import java.math.BigDecimal;

/** Presents a transaction and its features to the rule DSL (E.6: request fields and features). */
final class FeatureSubject implements RuleSubject {

  private final Transaction transaction;
  private final Scoring scoring;

  FeatureSubject(Transaction transaction, Scoring scoring) {
    this.transaction = transaction;
    this.scoring = scoring;
  }

  @Override
  public FieldValue value(String field) {
    return switch (field) {
      case "account_id" -> FieldValue.category(transaction.accountToken());
      case "counterparty_id" -> FieldValue.category(transaction.counterpartyToken());
      case "currency" -> FieldValue.category(transaction.amount().currency().name());
      case "channel" -> FieldValue.category(transaction.channel().name());
      case "device_fingerprint" -> FieldValue.category(transaction.deviceToken());
      case "merchant_category_code" -> FieldValue.category(transaction.merchantCategoryCode());
      case "agent_id" -> FieldValue.category(transaction.agentToken());
      case "counterparty_country" -> FieldValue.category(transaction.counterpartyCountry());
      case "amount" -> FieldValue.of(transaction.amount().amount());
      case "amount_rwf" -> FieldValue.of(transaction.amountRwf());
      case "latitude" -> FieldValue.of(BigDecimal.valueOf(transaction.latitude()));
      case "longitude" -> FieldValue.of(BigDecimal.valueOf(transaction.longitude()));
      default -> scoring.features().getOrDefault(field, FieldValue.MISSING);
    };
  }
}
