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
becomes the feature's training median before the forest sees it. The medians, like the forest and
its reference distribution, are M4's (`training.anomaly`): this module fits nothing.
"""

from __future__ import annotations

import functools
import math
from bisect import bisect_right
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np

from fraudshield_ml.training.anomaly import CONTAMINATION as CONTAMINATION_FITTED
from fraudshield_ml.training.anomaly import AnomalyModel

#: D-06 and E.4: the contamination M4's forest is fitted with. It moves scikit-learn's own
#: `decision_function` offset, not `score_samples`, so it changes nothing served here.
CONTAMINATION = CONTAMINATION_FITTED


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
        """scikit-learn's `score_samples` for one row: negative, lower is more anomalous.

        Every tree is walked at once, one level per step, over padded arrays (`_packed`): about
        ten numpy steps instead of a Python loop per tree. `path_length` is the per-tree reference
        the packed walk is tested against.
        """
        left, right, feature, threshold, leaf = self._packed
        filled = np.asarray(self.impute(row), dtype=np.float32).astype(np.float64)
        rows = np.arange(left.shape[0])
        node = np.zeros(left.shape[0], dtype=np.int64)
        while True:
            children = left[rows, node]
            active = children != -1
            if not active.any():
                break
            goes_left = filled[feature[rows, node]] <= threshold[rows, node]
            node = np.where(active, np.where(goes_left, children, right[rows, node]), node)
        depth = float(leaf[rows, node].sum())
        denominator = len(self.trees) * average_path_length(self.max_samples)
        return -(2.0 ** (-depth / denominator)) if denominator else -1.0

    @functools.cached_property
    def _packed(self) -> tuple[Any, Any, Any, Any, Any]:
        """The trees as padded [tree, node] arrays, with features mapped to row positions."""
        width = max(len(t.left) for t in self.trees)
        shape = (len(self.trees), width)
        left = np.full(shape, -1, dtype=np.int64)
        right = np.full(shape, -1, dtype=np.int64)
        feature = np.zeros(shape, dtype=np.int64)
        threshold = np.zeros(shape, dtype=np.float64)
        leaf = np.zeros(shape, dtype=np.float64)
        for i, t in enumerate(self.trees):
            n = len(t.left)
            left[i, :n], right[i, :n] = t.left, t.right
            # A leaf's feature is -2 in scikit-learn; it is never read, so map it to column 0.
            feature[i, :n] = [t.features[f] if f >= 0 else 0 for f in t.feature]
            threshold[i, :n], leaf[i, :n] = t.threshold, t.leaf_value
        return left, right, feature, threshold, leaf

    def raw_reference(self, row: Sequence[float]) -> float:
        """The per-tree walk, kept as the oracle the packed walk is tested against."""
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


def export(model: AnomalyModel) -> Forest:
    """M4's fitted Isolation Forest (`training.anomaly.fit_anomaly`) as plain arrays.

    The medians and the reference distribution are M4's, unchanged; the trees are read from the
    fitted scikit-learn estimator. `test_the_export_scores_exactly_as_m4_s_model_does` holds the
    exported score equal to `AnomalyModel.raw` to 1e-12.
    """
    estimator = model.forest
    trees = []
    for fitted, features in zip(estimator.estimators_, estimator.estimators_features_, strict=True):
        structure = fitted.tree_
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
    return Forest(
        trees=tuple(trees),
        max_samples=int(estimator._max_samples),  # the value scoring normalises by
        medians=tuple(float(m) for m in model.medians),
        reference=tuple(float(r) for r in model.reference),
    )
