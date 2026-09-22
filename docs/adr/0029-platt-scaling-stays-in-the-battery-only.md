# 0029 — Platt scaling stays in the M4 battery; the production model is calibrated as D-05 says

- **Status:** Accepted
- **Date:** 2026-09-22
- **Decided by:** author, resolving M4 review finding M4-2; the owner may overrule
- **Requirements affected:** D-05, FR-02-03, ML-GATE-11

## Context

D-05 is binding: the production score is **one isotonic regression on the combined
0.55·p_xgb + 0.45·p_lgb**, fitted on the calibration split. The M4 battery
(`fs-features battery`) instead calibrated a single XGBoost booster with two-parameter Platt
scaling, with a docstring reason — at a few hundred fraud rows an isotonic fit has a step per
positive — and no ADR. The M4 review recorded that as a deviation from a binding resolution
(M4-2).

The production model now exists: `training/model.py` implements D-05 exactly, and
`fs-features gate` measures ML-GATE-11 on its calibrated `ensemble_score`.

## Decision

1. **Every gate figure and every production score uses D-05's calibration.** ECE for ML-GATE-11,
   the operating points of D-02 and SHAP coverage are all measured on the isotonic-calibrated
   ensemble, never on the battery's Platt-scaled booster.
2. **The battery keeps Platt scaling, as a diagnostic of a different model.** The battery studies
   one fixed XGBoost booster — ablations, breakdowns, the variant hold-out, leave-one-country-out
   — and its calibration section describes *that* booster. Its committed evidence
   (`docs/benchmarks/m4_battery_pb61.txt`) was produced by the Platt code, and switching the
   method would leave that artefact describing code that no longer exists.
3. **The battery's calibration figures are not quoted as the model's calibration.** They are
   labelled as Platt on a single booster where they appear.

## Consequences

- Two calibration numbers exist, for two models. The model card and any paper table quote
  `fs-features gate`'s ECE; the battery's is a diagnostic.
- If the battery is ever re-run for publication, its calibration section should either move to
  isotonic or be dropped; which is the owner's choice.

## Alternatives rejected

- **Switch the battery to isotonic now.** It would make the committed battery evidence describe
  code that no longer produces it, which is the failure this project has recorded three times;
  and it would not change a gate figure, since none is taken from the battery.
- **Use Platt for the production model as well.** D-05 is binding, and its reason — calibrating
  the combined score rather than each model — does not depend on the calibrator's shape.
