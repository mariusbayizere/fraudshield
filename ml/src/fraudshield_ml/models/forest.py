"""Isolation Forest (D-06), exported from scikit-learn to plain arrays and scored without it.

**Why not pickle.** scikit-learn persists an estimator only by pickling it, and unpickling runs
code. A served bundle is downloaded from the model registry, so loading one must not be able to
execute anything: every part of a bundle is JSON or a booster's own text format. The trees an
Isolation Forest grows are five arrays each, and its score is arithmetic over them, so exporting
them loses nothing — `test_the_export_scores_exactly_as_scikit_learn_does` holds the two equal.

**What the score is.** scikit-learn's `score_samples` is negative and unbounded in practice. D-06
defines the served `anomaly_score` as the percentile rank of the *negated* raw score against the
training reference distribution, which this module keeps sorted beside the trees; the raw score is
served too, as `anomaly_raw`.

**Missing values are imputed, not routed.** E.4 fits the forest "on imputed features": each NaN
becomes the feature's training median before the forest sees it, both when fitting and here.
"""

from __future__ import annotations

import math
import warnings
from bisect import bisect_right
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np

#: D-06 and E.4: the contamination the forest is fitted with. It moves scikit-learn's own
#: `decision_function` offset, not `score_samples`, so it changes nothing served here; it is
#: recorded so the bundle states how the forest was fitted.
CONTAMINATION = 0.01
TREES = 100


def average_path_length(n: float) -> float:
    """The average path length of an unsuccessful search in a binary tree of `n` samples.

    The normalising constant c(n) of Liu, Ting and Zhou (2008), with scikit-learn's handling of
    one and two samples.
    """
    if n <= 1:
        return 0.0
    if n == 2:
        return 1.0
    return 2.0 * (math.log(n - 1.0) + np.euler_gamma) - 2.0 * (n - 1.0) / n


@dataclass(frozen=True)
class Tree:
    """One isolation tree: a node is a leaf when `left[node] == -1`."""

    features: tuple[int, ...]
    left: tuple[int, ...]
    right: tuple[int, ...]
    feature: tuple[int, ...]
    threshold: tuple[float, ...]
    #: Per node: depth + c(samples reaching it) - 1, the term scikit-learn adds at a leaf.
    leaf_value: tuple[float, ...]

    def path_length(self, row: Sequence[float]) -> float:
        node = 0
        while self.left[node] != -1:
            # scikit-learn's trees compare float32 inputs; the cast keeps a value that sits on a
            # threshold on the same side it fell on while the tree was grown.
            value = float(np.float32(row[self.features[self.feature[node]]]))
            node = self.left[node] if value <= self.threshold[node] else self.right[node]
        return self.leaf_value[node]


@dataclass(frozen=True)
class Forest:
    """An exported Isolation Forest, its imputation medians and its reference distribution."""

    trees: tuple[Tree, ...]
    max_samples: int
    medians: tuple[float, ...]
    #: Negated raw scores of the training rows, ascending: what a percentile is ranked against.
    reference: tuple[float, ...]

    def impute(self, row: Sequence[float]) -> list[float]:
        return [m if math.isnan(v) else v for v, m in zip(row, self.medians, strict=True)]

    def raw(self, row: Sequence[float]) -> float:
        """scikit-learn's `score_samples` for one row: negative, lower is more anomalous."""
        filled = self.impute(row)
        depth = sum(tree.path_length(filled) for tree in self.trees)
        denominator = len(self.trees) * average_path_length(self.max_samples)
        return -(2.0 ** (-depth / denominator)) if denominator else -1.0

    def percentile(self, raw: float) -> float:
        """D-06's `anomaly_score`: the empirical CDF of `-raw` over the training reference."""
        return bisect_right(self.reference, -raw) / len(self.reference)

    def to_json(self) -> dict[str, Any]:
        return {
            "max_samples": self.max_samples,
            "contamination": CONTAMINATION,
            "medians": list(self.medians),
            "reference": list(self.reference),
            "trees": [
                {
                    "features": list(t.features),
                    "left": list(t.left),
                    "right": list(t.right),
                    "feature": list(t.feature),
                    "threshold": list(t.threshold),
                    "leaf_value": list(t.leaf_value),
                }
                for t in self.trees
            ],
        }

    @staticmethod
    def from_json(data: dict[str, Any]) -> Forest:
        return Forest(
            trees=tuple(
                Tree(
                    features=tuple(int(v) for v in t["features"]),
                    left=tuple(int(v) for v in t["left"]),
                    right=tuple(int(v) for v in t["right"]),
                    feature=tuple(int(v) for v in t["feature"]),
                    threshold=tuple(float(v) for v in t["threshold"]),
                    leaf_value=tuple(float(v) for v in t["leaf_value"]),
                )
                for t in data["trees"]
            ),
            max_samples=int(data["max_samples"]),
            medians=tuple(float(v) for v in data["medians"]),
            reference=tuple(float(v) for v in data["reference"]),
        )


def fit(matrix: Sequence[Sequence[float]], seed: int) -> tuple[Forest, Any]:
    """Fit on the training rows, export, and return the fitted estimator for parity checks."""
    from sklearn.ensemble import IsolationForest  # type: ignore[import-untyped]  # noqa: PLC0415

    array = np.asarray(matrix, dtype=float)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # an all-NaN column is handled below
        medians = np.nanmedian(array, axis=0)
    # A column that is NaN on every training row has no median; it imputes to zero, a constant
    # the trees can never split on, which is what an all-missing feature should contribute.
    medians = np.where(np.isnan(medians), 0.0, medians)
    filled = np.where(np.isnan(array), medians, array)
    model = IsolationForest(
        n_estimators=TREES, contamination=CONTAMINATION, random_state=seed, n_jobs=1
    ).fit(filled)
    trees = []
    for estimator, features in zip(model.estimators_, model.estimators_features_, strict=True):
        structure = estimator.tree_
        depths = structure.compute_node_depths()
        trees.append(
            Tree(
                features=tuple(int(f) for f in features),
                left=tuple(int(v) for v in structure.children_left),
                right=tuple(int(v) for v in structure.children_right),
                feature=tuple(int(v) for v in structure.feature),
                threshold=tuple(float(v) for v in structure.threshold),
                leaf_value=tuple(
                    float(d) + average_path_length(float(n)) - 1.0
                    for d, n in zip(depths, structure.n_node_samples, strict=True)
                ),
            )
        )
    reference = sorted(float(-s) for s in model.score_samples(filled))
    forest = Forest(
        trees=tuple(trees),
        max_samples=int(model._max_samples),  # the value scoring normalises by
        medians=tuple(float(m) for m in medians),
        reference=tuple(reference),
    )
    return forest, model
