"""E.5's baselines that are not parts of the ensemble: a rule engine, logistic regression, a forest.

XGBoost alone, LightGBM alone and the Isolation Forest alone are the ensemble's own parts and are
scored from `model` and `anomaly`; the single-feature floor is `smoke.floor_from`. These three are
the models a reader would ask about next.

All three are fitted on the training rows only. The two scikit-learn models take median-imputed
input with medians from the training rows (D-04: imputation is for the baselines and the
Isolation Forest, never for the boosters), and weight classes rather than oversample (E.4).
"""

from __future__ import annotations

import math
from collections.abc import Sequence

from fraudshield_ml.training.anomaly import training_medians

#: The status quo E.5 describes: amount thresholds and an MCC blocklist. The list is the
#: conventional high-risk set — money transfer, cash, quasi-cash, gambling — and is ASSUMED, not
#: taken from any institution's rules. On this benchmark 4829 is every person-to-person transfer
#: and 6011 every agent cash-out, which is what such a list would catch in a mobile-money market.
MCC_BLOCKLIST = frozenset({"4829", "6011", "6051", "7995"})
#: The amount rule fires above this quantile of the training rows' legitimate amounts.
AMOUNT_QUANTILE = 0.99


def rule_engine(
    amount_log1p: Sequence[float],
    mcc: Sequence[str] | None,
    labels: Sequence[bool],
    train: Sequence[int],
) -> list[float]:
    """How many of the two rules fire, 0, 1 or 2. A rule engine ranks coarsely, and says so.

    The amount threshold is fitted on the training rows' legitimate amounts. Without an MCC column
    (a cache written before it was carried) only the amount rule is scored, and the caller is
    told by the score's range, not silently.
    """
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    legitimate = [amount_log1p[i] for i in train if not labels[i]]
    threshold = float(np.quantile(legitimate, AMOUNT_QUANTILE))
    scores = []
    for i, amount in enumerate(amount_log1p):
        fired = float(amount > threshold)
        if mcc is not None:
            fired += float(mcc[i] in MCC_BLOCKLIST)
        scores.append(fired)
    return scores


def _imputed(
    matrix: Sequence[Sequence[float]], train: Sequence[int]
) -> tuple[list[list[float]], tuple[float, ...]]:
    medians = training_medians(matrix, train)
    return [
        [m if math.isnan(v) else v for v, m in zip(row, medians, strict=True)] for row in matrix
    ], medians


def logistic_regression(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    *,
    seed: int,
) -> list[float]:
    from sklearn.linear_model import LogisticRegression  # noqa: PLC0415 - heavy, only this path
    from sklearn.pipeline import make_pipeline  # noqa: PLC0415
    from sklearn.preprocessing import StandardScaler  # noqa: PLC0415

    rows, _ = _imputed(matrix, train)
    model = make_pipeline(
        StandardScaler(),
        LogisticRegression(max_iter=2000, class_weight="balanced", random_state=seed),
    )
    model.fit([rows[i] for i in train], [bool(labels[i]) for i in train])
    return [float(p) for p in model.predict_proba([rows[i] for i in test])[:, 1]]


def random_forest(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    *,
    seed: int,
) -> list[float]:
    from sklearn.ensemble import RandomForestClassifier  # noqa: PLC0415 - heavy, only this path

    rows, _ = _imputed(matrix, train)
    model = RandomForestClassifier(
        n_estimators=200,
        min_samples_leaf=5,
        class_weight="balanced_subsample",
        n_jobs=1,
        random_state=seed,
    )
    model.fit([rows[i] for i in train], [bool(labels[i]) for i in train])
    return [float(p) for p in model.predict_proba([rows[i] for i in test])[:, 1]]
