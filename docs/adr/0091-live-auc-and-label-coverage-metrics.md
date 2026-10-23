# 0091 — Live AUC and label coverage join the E.10 metrics; the AUC-drop alert needs coverage

- **Status:** Accepted
- **Decided by:** repository owner, 2026-09-22 (`docs/parallel/M9_updates.md` decisions 2 and 3:
  add `fs_model_auc_roc{alias}`, a `version` label on `fs_model_version_info`, and
  `fs_model_label_coverage{alias}`; the AUC-drop alert fires only above a stated coverage minimum);
  the M9 agent wrote this record and the amendment.
- **Date:** 2026-09-22
- **Requirements affected:** OPS-OBS-01, OPS-OBS-03
- **Defects referenced:** D-11, D-50

## Context

SRS 8.2 (ML Drift Monitoring): "AUC-ROC drop > 0.03 from baseline triggers retraining evaluation".
Build prompt Part E.10, the single catalogue of FraudShield metrics that
`infrastructure/checks/metric_catalogue.py` enforces, had no live AUC metric, so the alert could
not be written. `fs_model_version_info{alias}` also had no version label, so a dashboard could
show that an alias exists but not which model it points to.

Live AUC is computed on labels that arrive hours to weeks after the decision (E.3). Early in a
window, or on a quiet channel, the labelled subset is small and skewed towards what analysts
reviewed first, and its AUC is noise. D-11 already sets the floor for trusting live labels: below
30% coverage the promotion gate reports "Insufficient labels" and refuses to decide.

## Options considered

1. **Alert on live AUC alone.** Pages on noise whenever coverage is low; teaches the team to
   ignore the alert.
2. **Alert on live AUC only while coverage is at least 30%, against the model's own covered
   history.** Consistent with D-11; silent (and visibly so on the dashboard) when labels are too
   sparse to judge.
3. **Compare with the AUC recorded at promotion.** Needs one more metric per model version; can be
   added later without changing the alert's coverage rule.

## Decision

Option 2.

1. **Part E.10 is amended** (build prompt, E.10 metrics list): `fs_model_version_info{alias,version}`,
   `fs_model_auc_roc{alias}` and `fs_model_label_coverage{alias}`.
   - `alias` is the MLflow alias without `@` (`production`, `shadow`, `previous_production`, D-50);
     `version` is the registered model version the alias points to.
   - `fs_model_auc_roc{alias}`: AUC-ROC of that alias's scores on the transactions whose labels are
     available (`label_available_at` ≤ now), over the evaluation window of the hourly drift job
     (OPS-OBS-03).
   - `fs_model_label_coverage{alias}`: labelled share, in [0, 1], of the transactions in that same
     window. Both gauges are published together by the same job run.
2. **Coverage minimum: 0.30** (D-11's threshold). An hour whose coverage is below it is excluded
   from the baseline and cannot fire the alert.
3. **Baseline:** the mean of the covered hourly values of `@production` over the last 30 days,
   required to contain at least 72 covered hours.
4. **Alert** `FraudShieldModelAucDrop` (severity ticket, team ml): covered live AUC more than 0.03
   below the baseline for 2 hours. Rules in `infrastructure/prometheus/rules/`; tests in
   `infrastructure/prometheus/tests/ml_test.yml`: drop 0.035 fires, drop 0.025 is silent, a large
   drop at 20% coverage is silent, and a drop with less than 72 covered baseline hours is silent.

## Consequences

- The ML worker (M5/M4 owners) must publish the two new gauges and the `version` label; the
  metric-catalogue check rejects any other names.
- After a promotion the 30-day baseline mixes the old and new versions for up to 30 days. A better
  model lowers no alarm; a worse one is caught against the mix. Option 3 removes this if needed.
- The Model health dashboard shows live AUC, coverage and the alert level, so "silent because
  coverage is low" is visible rather than mistaken for "healthy".
