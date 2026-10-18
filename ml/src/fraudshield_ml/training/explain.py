"""Explanations for D-05's ensemble: exact per model, combined in margin space (D-05 (4), E.4).

Each booster's contributions are exact TreeSHAP, read from the booster itself (`pred_contribs` for
XGBoost, `pred_contrib` for LightGBM) at the round count early stopping chose — the same trees
`Ensemble.raw` scores with. They are exact **in each model's margin (log-odds) space**, which is
where FR-02-04's additivity test is run, per model, at a 0.001 tolerance.

The analyst sees the weighted combination 0.55·φ_xgb + 0.45·φ_lgb, also in margin space, with its
base value and the combined margin it sums to. **It does not sum to the calibrated probability and
is never described as if it did:** the ensemble combines probabilities and then applies an
isotonic map, neither of which is linear in margin. `docs/ml/explainability.md` says this for a
reader.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from fraudshield_ml.training.ensemble import LIGHTGBM_WEIGHT, XGBOOST_WEIGHT
from fraudshield_ml.training.model import Ensemble

#: FR-02-04's additivity tolerance, in margin space.
ADDITIVITY_TOLERANCE = 0.001
#: D-02's flag threshold: MEDIUM and above, the rows ML-GATE-10 requires explanations for.
EXPLAINED_FROM = 0.60
TOP = 5


@dataclass(frozen=True)
class Contribution:
    """One entry of `shap_top5`, in E.4's shape."""

    feature: str
    value: float
    shap: float
    direction: str
    template_key: str


@dataclass(frozen=True)
class Explanation:
    """One row's explanation. `shap` sums to `margin` minus `base`, in margin space."""

    base: float
    margin: float
    shap: tuple[float, ...]
    top: tuple[Contribution, ...]
    #: Largest per-model gap between summed contributions and that model's own margin.
    additivity_error: float

    @property
    def complete(self) -> bool:
        return len(self.top) == TOP and self.additivity_error <= ADDITIVITY_TOLERANCE


def _per_model(
    ensemble: Ensemble, rows: Sequence[Sequence[float]]
) -> tuple[list[list[float]], list[float], list[list[float]], list[float]]:
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it
    import xgboost as xgb  # noqa: PLC0415 - heavy, and only this path needs it

    # float32, as `Ensemble.raw` scores: an explanation of a different routing is not one.
    array = np.asarray(rows, dtype=np.float32)
    matrix = xgb.DMatrix(array, missing=float("nan"))
    span = (0, ensemble.xgboost_rounds)
    phi_xgb = ensemble.xgboost.predict(matrix, pred_contribs=True, iteration_range=span)
    m_xgb = ensemble.xgboost.predict(matrix, output_margin=True, iteration_range=span)
    rounds = ensemble.lightgbm_rounds
    phi_lgb = ensemble.lightgbm.predict(array, pred_contrib=True, num_iteration=rounds)
    m_lgb = ensemble.lightgbm.predict(array, raw_score=True, num_iteration=rounds)
    return (
        [[float(v) for v in row] for row in phi_xgb],
        [float(v) for v in m_xgb],
        [[float(v) for v in row] for row in phi_lgb],
        [float(v) for v in m_lgb],
    )


def explain(
    ensemble: Ensemble, rows: Sequence[Sequence[float]], names: Sequence[str]
) -> list[Explanation]:
    """Per-model exact contributions, checked, then combined and ranked for the analyst."""
    phi_xgb, m_xgb, phi_lgb, m_lgb = _per_model(ensemble, rows)
    width = len(names)
    explanations = []
    for i, row in enumerate(rows):
        px, pl = phi_xgb[i], phi_lgb[i]
        if len(px) != width + 1 or len(pl) != width + 1:
            raise ValueError(
                f"expected {width} features plus a bias column from each model, got {len(px)} "
                f"and {len(pl)}; a mismatch would attribute each feature to its neighbour"
            )
        error = max(abs(sum(px) - m_xgb[i]), abs(sum(pl) - m_lgb[i]))
        combined = [XGBOOST_WEIGHT * a + LIGHTGBM_WEIGHT * b for a, b in zip(px, pl, strict=True)]
        shap, base = combined[:width], combined[width]
        ranked = sorted(range(width), key=lambda j: abs(shap[j]), reverse=True)[:TOP]
        top = tuple(
            Contribution(
                feature=names[j],
                value=float(row[j]),
                shap=shap[j],
                direction="increases_risk" if shap[j] > 0 else "decreases_risk",
                template_key=f"shap.{names[j]}.{'up' if shap[j] > 0 else 'down'}",
            )
            for j in ranked
        )
        explanations.append(
            Explanation(
                base=base,
                margin=XGBOOST_WEIGHT * m_xgb[i] + LIGHTGBM_WEIGHT * m_lgb[i],
                shap=tuple(shap),
                top=top,
                additivity_error=error,
            )
        )
    return explanations


def coverage(scores: Sequence[float], explanations: Sequence[Explanation]) -> tuple[float, int]:
    """ML-GATE-10: the share of HIGH and MEDIUM rows carrying a complete, additive explanation.

    Returns the share and the number of rows it is over. A share over zero rows is undefined, and
    is reported as NaN rather than as 100% of nothing.
    """
    flagged = [e for s, e in zip(scores, explanations, strict=True) if s >= EXPLAINED_FROM]
    if not flagged:
        return float("nan"), 0
    return sum(1 for e in flagged if e.complete) / len(flagged), len(flagged)
