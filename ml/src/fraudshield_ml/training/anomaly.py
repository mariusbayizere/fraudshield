"""D-06's Isolation Forest, and the score it has to report.

The SRS gives the anomaly score as a value in [-1, 1] and routes anything above 0.7 to review.
scikit-learn's `score_samples` is neither: it is negative, roughly in [-1, 0], and higher means
*more normal*. D-06's resolution defines two quantities and keeps them apart:

- `anomaly_raw` is `score_samples` as scikit-learn returns it. The SRS unit test on [-1, 1] applies
  to this one.
- `anomaly_score` in [0, 1] is the percentile rank of the negated raw score against the **training**
  rows' own negated scores, stored with the model. 0.99 means "more anomalous than 99% of what the
  forest was fitted on", which is a statement an alert budget can be sized against (D-10).

The forest cannot take NaN, so its input is median-imputed with medians taken from the training
rows alone (D-04: imputation applies to this model and to the baselines, never to the boosters,
which split on missingness as signal). A median computed over scored rows would be a quiet leak.
"""

from __future__ import annotations

import bisect
import math
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

#: E.4: contamination 0.01.
CONTAMINATION = 0.01
TREES = 200


@dataclass(frozen=True)
class AnomalyModel:
    forest: Any
    medians: tuple[float, ...]
    #: Sorted negated raw scores of the training rows — the reference the percentile is taken
    #: against. Stored with the model, because a percentile against anything else would change
    #: meaning whenever the scored population did.
    reference: tuple[float, ...]

    def impute(self, rows: Sequence[Sequence[float]]) -> list[list[float]]:
        return [
            [m if math.isnan(v) else v for v, m in zip(row, self.medians, strict=True)]
            for row in rows
        ]

    def raw(self, rows: Sequence[Sequence[float]]) -> list[float]:
        """scikit-learn's `score_samples`: roughly [-1, 0], higher is more normal."""
        return [float(v) for v in self.forest.score_samples(self.impute(rows))]

    def score(self, rows: Sequence[Sequence[float]]) -> list[float]:
        """Percentile rank in [0, 1] of each row's anomalousness against the training rows."""
        n = len(self.reference)
        return [bisect.bisect_right(self.reference, -r) / n for r in self.raw(rows)]


def _medians(matrix: Sequence[Sequence[float]], train: Sequence[int]) -> tuple[float, ...]:
    import statistics  # noqa: PLC0415 - only this path needs it

    width = len(matrix[train[0]])
    medians = []
    for column in range(width):
        defined = [matrix[i][column] for i in train if not math.isnan(matrix[i][column])]
        # A column missing on every training row carries nothing; 0.0 is as good as any constant.
        medians.append(statistics.median(defined) if defined else 0.0)
    return tuple(medians)


def fit_anomaly(
    matrix: Sequence[Sequence[float]], train: Sequence[int], *, seed: int
) -> AnomalyModel:
    """Fit on the training rows only. Labels are not used: this is the unsupervised detector."""
    from sklearn.ensemble import IsolationForest  # noqa: PLC0415 - heavy, only this path

    if not train:
        raise ValueError("no training rows to fit the Isolation Forest on")
    medians = _medians(matrix, train)
    unfitted = AnomalyModel(forest=None, medians=medians, reference=())
    fitting = unfitted.impute([matrix[i] for i in train])
    forest = IsolationForest(
        n_estimators=TREES, contamination=CONTAMINATION, random_state=seed, n_jobs=1
    )
    forest.fit(fitting)
    reference = tuple(sorted(-float(v) for v in forest.score_samples(fitting)))
    return AnomalyModel(forest=forest, medians=medians, reference=reference)
