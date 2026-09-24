# Explainability (D-05 (4), FR-02-04, E.4)

What an analyst is shown for a flagged transaction, what it is exact about, and what it must never
be described as.

## What is computed

Code: `ml/src/fraudshield_ml/training/explain.py`. Tests: `ml/tests/training/test_explain.py`.

1. **Per model, exact.** Each booster's contributions are exact TreeSHAP read from the booster
   itself — `pred_contribs` for XGBoost, `pred_contrib` for LightGBM — at the round count early
   stopping chose, so they explain the same trees the ensemble scores with. No sampling and no
   `shap` package: tree SHAP is exact for trees, and both libraries implement it natively.
2. **Checked per model, in margin space.** For every row, each model's contributions plus its bias
   must sum to that model's own margin (log-odds) within **0.001** (FR-02-04). This is where
   additivity is mathematically exact, so this is where it is tested.
3. **Combined for the analyst.** The displayed contribution of feature *j* is
   `0.55·φ_xgb[j] + 0.45·φ_lgb[j]`, the same weights as the ensemble. The combined contributions
   plus the combined base value sum to the combined margin `0.55·m_xgb + 0.45·m_lgb`.
4. **Top five.** Features ranked by the magnitude of their combined contribution, stored as
   `shap_top5 = [{feature, value, shap, direction, template_key}]`. `direction` is
   `increases_risk` or `decreases_risk`; `template_key` (`shap.<feature>.<up|down>`) selects the
   analyst-facing sentence.

## What it is not

**The contributions do not sum to the calibrated probability, and must never be presented as if
they did.** The ensemble averages *probabilities* (not margins) and then applies an isotonic map,
and neither step is linear in margin space. So the display shows the base value, the combined
margin the contributions sum to, and — separately — the final calibrated `ensemble_score`. A
waterfall that ended at the calibrated probability would be arithmetic that does not hold.

A contribution is a statement about this model on this row, not about fraud in general: on this
benchmark one velocity feature separates fraud almost alone (PB-46), and the explanations will
say so.

## Coverage (ML-GATE-10)

The gate requires an explanation for 100% of HIGH and MEDIUM transactions (score ≥ 0.60, D-02's
flag threshold). `explain.coverage` counts a row as covered only if its explanation has all five
entries **and** passed the per-model additivity check; a row whose contributions do not add up is
not an explanation, whatever it displays. Coverage over zero flagged rows is reported as
undefined, not as 100%.
