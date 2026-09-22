"""The production model D-05 specifies: two boosters, one combination, one calibrator.

1. XGBoost and LightGBM, each with class-imbalance weighting (E.4: `scale_pos_weight` /
   `is_unbalance`, no oversampling) and early stopping on the **validation** rows, monitored by
   average precision — E.4's search objective, and the right one at a 1% base rate, where AUC is
   dominated by the easy negatives.
2. Raw probabilities combined as 0.55·p_xgb + 0.45·p_lgb.
3. **One** isotonic regression on the combined score, fitted on the **calibration** rows, which D-07
   places after validation and before the embargo. `ensemble_score` is that calibrated value, and
   it is the score ECE (ML-GATE-11) and the operating points (D-02) are measured on. Each booster
   also gets its own isotonic calibrator on the same rows, for display and diagnostics only.

Imbalance weighting inflates every raw probability, which is exactly why step 3 exists: the
weighted boosters rank well and are badly calibrated, and a monotone map fixes the second without
touching the first.

**Scores are float32 throughout, because serving is.** The ONNX export M5 serves (E.4) computes
in float32, and so does XGBoost internally. LightGBM does not: it compares a double input with a
double threshold, so on the release-scale test period 398 of 101,909 rows scored differently once
exported, up to 0.23 in probability — 310 because an input float32 cannot represent crossed a
split when rounded, the rest because a double threshold moved when stored as float32. So every
input is rounded to float32 before either booster sees it, and each LightGBM threshold is
replaced by the largest float32 not above it. For a float32 input `x`, `x <= t` and
`x <= floor32(t)` then agree exactly, and the model evaluated here is the model exported.

**The three row sets must be disjoint, and `fit_ensemble` refuses otherwise** rather than leaving
it to the caller. A calibrator fitted on rows the boosters trained or stopped on learns how
confident the model is about data it has seen, which is the leak D-05's separate split exists to
prevent — and it produces plausible output either way, so nothing downstream would notice.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Any

from fraudshield_ml.training.ensemble import (
    LIGHTGBM_PARAMETERS,
    XGBOOST_PARAMETERS,
    ensemble_score,
)

#: Upper bound on boosting rounds. Early stopping decides the actual count; this only stops a
#: model that never stops improving from running forever.
MAX_ROUNDS = 2000
#: Rounds without a validation improvement before stopping.
PATIENCE = 50

#: E.4's objective is validation average precision, so both boosters stop on it.
XGBOOST_TRAINING = {**XGBOOST_PARAMETERS, "eval_metric": "aucpr"}
LIGHTGBM_TRAINING = {**LIGHTGBM_PARAMETERS, "metric": "average_precision"}


class OverlappingRowsError(ValueError):
    """Train, validation and calibration rows were not disjoint."""


@dataclass(frozen=True)
class Scores:
    """What one row scores, in the shape the ScoringResult carries it (D-05)."""

    ensemble: list[float]
    xgboost: list[float]
    lightgbm: list[float]
    raw_combined: list[float]


@dataclass(frozen=True)
class Ensemble:
    """The fitted model. The boosters are kept native so exact TreeSHAP can read them."""

    xgboost: Any
    lightgbm: Any
    xgboost_rounds: int
    lightgbm_rounds: int
    calibrator: Any
    xgboost_calibrator: Any
    lightgbm_calibrator: Any
    scale_pos_weight: float

    def raw(self, rows: Sequence[Sequence[float]]) -> tuple[list[float], list[float]]:
        """Each booster's uncalibrated probability, at its early-stopped round count."""
        import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it
        import xgboost as xgb  # noqa: PLC0415 - heavy, and only this path needs it

        array = np.asarray(rows, dtype=np.float32)
        p_xgb = self.xgboost.predict(
            xgb.DMatrix(array, missing=float("nan")), iteration_range=(0, self.xgboost_rounds)
        )
        p_lgb = self.lightgbm.predict(array, num_iteration=self.lightgbm_rounds)
        return [float(p) for p in p_xgb], [float(p) for p in p_lgb]

    def score(self, rows: Sequence[Sequence[float]]) -> Scores:
        p_xgb, p_lgb = self.raw(rows)
        combined = ensemble_score(p_xgb, p_lgb)
        return Scores(
            ensemble=[float(v) for v in self.calibrator.predict(combined)],
            xgboost=[float(v) for v in self.xgboost_calibrator.predict(p_xgb)],
            lightgbm=[float(v) for v in self.lightgbm_calibrator.predict(p_lgb)],
            raw_combined=combined,
        )


@dataclass(frozen=True)
class RowSplit:
    """D-05's three row sets, as indices into one matrix. Disjoint, or it cannot be built."""

    train: tuple[int, ...]
    validation: tuple[int, ...]
    calibration: tuple[int, ...]

    def __post_init__(self) -> None:
        sets = {
            "train": set(self.train),
            "validation": set(self.validation),
            "calibration": set(self.calibration),
        }
        for name, rows in sets.items():
            if not rows:
                raise ValueError(f"no {name} rows: D-05 needs train, validation and calibration")
        for a, b in (
            ("train", "validation"),
            ("train", "calibration"),
            ("validation", "calibration"),
        ):
            shared = sets[a] & sets[b]
            if shared:
                raise OverlappingRowsError(
                    f"{len(shared)} rows are in both {a} and {b}; D-05 fits the calibrator on a "
                    "split the boosters never saw, and a calibrator fitted on seen rows learns the "
                    "model's confidence about its own training data"
                )


def _floor32(value: float) -> float:
    """The largest float32 that is not above `value`."""
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    rounded = np.float32(value)
    if float(rounded) > value:
        rounded = np.nextafter(rounded, np.float32(-np.inf))
    return float(rounded)


def float32_exact(booster: Any, rounds: int) -> Any:
    """`booster` truncated to `rounds` and made to route float32 inputs exactly as ONNX will.

    Every numeric threshold `t` becomes `floor32(t)`: for any float32 input the comparison
    `x <= t` is then unchanged, and the threshold survives the float32 export without moving.
    The `tree_sizes` header indexes the text by byte length, which the rewrite changes, so it is
    dropped and LightGBM parses the trees in order instead.
    """
    import re  # noqa: PLC0415 - only this path needs it

    import lightgbm as lgb  # noqa: PLC0415 - heavy, and only this path needs it

    text = booster.model_to_string(num_iteration=rounds)
    for decision in re.findall(r"^decision_type=(.*)$", text, flags=re.MULTILINE):
        if any(int(d) & 1 for d in decision.split()):
            raise ValueError(
                "a categorical split cannot be made float32-exact by moving its threshold; "
                "the features are numeric by construction, so this is a change to the matrix"
            )
    text = re.sub(
        r"^threshold=(.*)$",
        lambda m: "threshold=" + " ".join(repr(_floor32(float(v))) for v in m.group(1).split()),
        text,
        flags=re.MULTILINE,
    )
    text = re.sub(r"^tree_sizes=.*\n", "", text, flags=re.MULTILINE)
    return lgb.Booster(model_str=text)


def _isotonic(scores: Sequence[float], labels: Sequence[bool]) -> Any:
    from sklearn.isotonic import IsotonicRegression  # noqa: PLC0415 - heavy, only this path

    # Clipped at both ends: a production score outside the calibration range maps to the nearest
    # calibrated value rather than to NaN, which is what `out_of_bounds="nan"` would return.
    model = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    model.fit(list(scores), [1.0 if y else 0.0 for y in labels])
    return model


def fit_ensemble(
    matrix: Sequence[Sequence[float]], labels: Sequence[bool], split: RowSplit, *, seed: int
) -> Ensemble:
    """Fit D-05's ensemble on `split`, whose row sets are disjoint by construction."""
    train, validation, calibration = split.train, split.validation, split.calibration
    import lightgbm as lgb  # noqa: PLC0415 - heavy, and only this path needs it
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it
    import xgboost as xgb  # noqa: PLC0415 - heavy, and only this path needs it

    def rows(index: Sequence[int]) -> Any:
        return np.array([matrix[i] for i in index], dtype=np.float32)

    def targets(index: Sequence[int]) -> Any:
        return np.array([1.0 if labels[i] else 0.0 for i in index])

    positives = sum(1 for i in train if labels[i])
    if positives == 0 or positives == len(train):
        raise ValueError("the training rows hold a single class; there is nothing to fit")
    # Negatives per positive in the training rows, the weight XGBoost's documentation gives.
    scale_pos_weight = (len(train) - positives) / positives

    booster = xgb.train(
        {**XGBOOST_TRAINING, "seed": seed, "scale_pos_weight": scale_pos_weight},
        xgb.DMatrix(rows(train), label=targets(train), missing=float("nan")),
        MAX_ROUNDS,
        evals=[
            (
                xgb.DMatrix(rows(validation), label=targets(validation), missing=float("nan")),
                "validation",
            )
        ],
        early_stopping_rounds=PATIENCE,
        verbose_eval=False,
    )
    lightgbm = lgb.train(
        {**LIGHTGBM_TRAINING, "seed": seed, "is_unbalance": True},
        lgb.Dataset(rows(train), label=targets(train)),
        num_boost_round=MAX_ROUNDS,
        valid_sets=[lgb.Dataset(rows(validation), label=targets(validation))],
        callbacks=[lgb.early_stopping(PATIENCE, verbose=False)],
    )
    exact = float32_exact(lightgbm, int(lightgbm.best_iteration))
    fitted = Ensemble(
        xgboost=booster,
        lightgbm=exact,
        xgboost_rounds=int(booster.best_iteration) + 1,
        lightgbm_rounds=int(exact.num_trees()),
        calibrator=None,
        xgboost_calibrator=None,
        lightgbm_calibrator=None,
        scale_pos_weight=scale_pos_weight,
    )
    held = [labels[i] for i in calibration]
    p_xgb, p_lgb = fitted.raw([matrix[i] for i in calibration])
    return replace(
        fitted,
        calibrator=_isotonic(ensemble_score(p_xgb, p_lgb), held),
        xgboost_calibrator=_isotonic(p_xgb, held),
        lightgbm_calibrator=_isotonic(p_lgb, held),
    )
