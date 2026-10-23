package io.github.mariusbayizere.fraudshield.analyst.scoring;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldCatalogue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The full scoring result of a transaction, for staff investigation (FR-02-01, D-12). */
@RestController
public class ScoringResultController {

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param jdbc JDBC template of the application role
   */
  public ScoringResultController(TenantTransactions tenants, JdbcTemplate jdbc) {
    this.tenants = Objects.requireNonNull(tenants);
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  /**
   * The latest scoring result of a transaction.
   *
   * @param jwt caller
   * @param transactionId the transaction
   * @return the scoring result
   */
  @ContractOperation("getScoringResult")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/api/v1/transactions/{transaction_id}/scoring-result")
  public Map<String, Object> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("transaction_id") UUID transactionId) {
    StaffClaims claims = AccessTokens.claims(jwt);
    List<Map<String, Object>> found =
        tenants.inTenant(
            claims.institutionId(),
            () ->
                jdbc.query(
                    """
                    SELECT id, transaction_id, ensemble_score, xgboost_score, lightgbm_score,
                      anomaly_score, risk_tier, shap_top5::text AS shap_top5,
                      feature_vector::text AS feature_vector, model_version, scoring_duration_ms,
                      ml_unavailable_fallback
                    FROM v_fraud_scores WHERE transaction_id = ?
                    ORDER BY scored_at DESC LIMIT 1
                    """,
                    (row, i) -> view(row),
                    transactionId));
    if (found.isEmpty()) {
      throw ProblemException.notFound("scoring result");
    }
    return found.getFirst();
  }

  private static Map<String, Object> view(ResultSet row) throws SQLException {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scoring_result_id", row.getObject("id", UUID.class));
    view.put("transaction_id", row.getObject("transaction_id", UUID.class));
    view.put("ensemble_score", row.getDouble("ensemble_score"));
    view.put("xgboost_score", row.getDouble("xgboost_score"));
    view.put("lightgbm_score", row.getDouble("lightgbm_score"));
    view.put("anomaly_score", row.getDouble("anomaly_score"));
    view.put("risk_tier", row.getString("risk_tier"));
    view.put("shap_top5", Views.rows(row.getString("shap_top5")));
    view.put("feature_vector", features(Views.object(row.getString("feature_vector"))));
    view.put("model_version", row.getString("model_version"));
    view.put("scoring_duration_ms", row.getInt("scoring_duration_ms"));
    view.put("ml_unavailable_fallback", row.getBoolean("ml_unavailable_fallback"));
    return view;
  }

  /**
   * All 44 features by name. The rule-based fallback computes none, so its stored vector is empty
   * and every feature is null: structurally missing, as D-04 writes a missing feature.
   */
  private static Map<String, Object> features(Map<String, Object> stored) {
    Map<String, Object> vector = new LinkedHashMap<>();
    for (String name : FieldCatalogue.featureNames()) {
      vector.put(name, stored.get(name));
    }
    return vector;
  }
}
