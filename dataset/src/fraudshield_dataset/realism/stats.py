"""Rank-based AUC and a small depth-limited decision tree, in NumPy (ADR 0022 section 7).

Only what the checks need: the single-feature AUC limit and the shortcut detector. Kept small and
tested against hand-computed cases, so the checks do not depend on a machine-learning library.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

DETECTOR_DEPTH = 3
MIN_LEAF_FLOOR = 5
MIN_LEAF_CAP = 50
LEAF_FRACTION = 20
Floats = NDArray[np.float64]
Bools = NDArray[np.bool_]


def auc(scores: Floats, labels: Bools) -> float:
    """Area under the ROC curve (Mann-Whitney U with average ranks for ties); 0.5 if undefined."""
    positives = int(labels.sum())
    negatives = labels.size - positives
    if positives == 0 or negatives == 0:
        return 0.5
    order = np.argsort(scores, kind="mergesort")
    sorted_scores = scores[order]
    ranks = np.empty(scores.size, dtype=np.float64)
    boundaries = np.flatnonzero(np.diff(sorted_scores)) + 1
    starts = np.concatenate(([0], boundaries))
    ends = np.concatenate((boundaries, [scores.size]))
    average = (starts + ends + 1) / 2.0
    ranks[order] = np.repeat(average, ends - starts)
    rank_sum = float(ranks[labels].sum())
    return (rank_sum - positives * (positives + 1) / 2.0) / (positives * negatives)


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval for a binomial proportion (the default z gives 95%).

    Used for monthly fraud rates: at a few hundred fraud rows a month the normal approximation
    is unusable near zero, while the Wilson interval stays inside [0, 1] and keeps its nominal
    coverage, so a rate that misses its target can be called noise or bias honestly.
    """
    if total <= 0:
        return (0.0, 0.0)
    rate = successes / total
    denominator = 1.0 + z**2 / total
    centre = (rate + z**2 / (2 * total)) / denominator
    spread = z * math.sqrt(rate * (1.0 - rate) / total + z**2 / (4 * total**2)) / denominator
    return (max(0.0, centre - spread), min(1.0, centre + spread))


# A cross-validated tree's AUC is noisier under the null than a single fixed score vector: its
# out-of-fold scores take few distinct values, and fitting noise and the overlap between training
# folds add variance. Measured at 0.96-0.98 of the analytic SD for a single feature but 1.17-1.26 of
# it for the depth-3 cross-validated tree (M2 principal review, MAJOR 1.3), so bands for the tree
# are widened by this factor. `test_stats.py` re-measures it and fails if it is not conservative.
CV_TREE_NULL_INFLATION = 1.3


def cv_auc_null_band(positives: int, negatives: int, z: float) -> float:
    """Half-width of a null band for a cross-validated tree AUC at ``z`` standard errors."""
    return z * CV_TREE_NULL_INFLATION * null_auc_stderr(positives, negatives)


def null_auc_stderr(positives: int, negatives: int) -> float:
    """Standard error of the AUC under the null hypothesis that it is 0.5.

    The Mann-Whitney U statistic has variance ``n1 * n0 * (n1 + n0 + 1) / 12`` when the labels are
    exchangeable, so an AUC computed over few positives is noisy by construction. The checks use
    this to size their tolerance band instead of trusting one fixed number at every sample size.
    """
    if positives <= 0 or negatives <= 0:
        return 0.0
    return math.sqrt((positives + negatives + 1) / (12.0 * positives * negatives))


def separation(scores: Floats, labels: Bools) -> float:
    """``max(AUC, 1 - AUC)``: how well a feature separates the classes in either direction."""
    value = auc(scores, labels)
    return max(value, 1.0 - value)


@dataclass(frozen=True)
class _Node:
    feature: int = -1
    threshold: float = 0.0
    left: _Node | None = None
    right: _Node | None = None
    value: float = 0.0


class ShallowTree:
    """Binary classification tree, Gini impurity, class-balanced weights, quantile thresholds."""

    def __init__(self, max_depth: int = 3, min_leaf: int = 50, thresholds: int = 32) -> None:
        self.max_depth = max_depth
        self.min_leaf = min_leaf
        self.thresholds = thresholds
        self._root: _Node | None = None

    def fit(self, features: Floats, labels: Bools) -> ShallowTree:
        positives = max(int(labels.sum()), 1)
        negatives = max(labels.size - positives, 1)
        weights = np.where(labels, 0.5 / positives, 0.5 / negatives)
        self._root = self._grow(features, labels, weights, depth=0)
        return self

    def _leaf(self, labels: Bools, weights: Floats) -> _Node:
        total = float(weights.sum())
        return _Node(value=float(weights[labels].sum()) / total if total > 0 else 0.5)

    def _grow(self, x: Floats, y: Bools, w: Floats, depth: int) -> _Node:
        if depth >= self.max_depth or y.size < 2 * self.min_leaf or y.all() or not y.any():
            return self._leaf(y, w)
        best: tuple[float, int, float] | None = None
        total = float(w.sum())
        parent = _gini(float(w[y].sum()), total)
        for feature in range(x.shape[1]):
            column = x[:, feature]
            candidates = np.unique(
                np.quantile(column, np.linspace(0, 1, self.thresholds + 2)[1:-1])
            )
            for threshold in candidates:
                left = column <= threshold
                count_left = int(left.sum())
                if count_left < self.min_leaf or y.size - count_left < self.min_leaf:
                    continue
                w_left, w_right = float(w[left].sum()), float(w[~left].sum())
                gain = parent - (
                    w_left / total * _gini(float(w[left & y].sum()), w_left)
                    + w_right / total * _gini(float(w[~left & y].sum()), w_right)
                )
                if best is None or gain > best[0]:
                    best = (gain, feature, float(threshold))
        if best is None or best[0] <= 0.0:
            return self._leaf(y, w)
        _, feature, threshold = best
        left = x[:, feature] <= threshold
        return _Node(
            feature=feature,
            threshold=threshold,
            left=self._grow(x[left], y[left], w[left], depth + 1),
            right=self._grow(x[~left], y[~left], w[~left], depth + 1),
        )

    def predict(self, features: Floats) -> Floats:
        if self._root is None:
            raise RuntimeError("the tree is not fitted")
        out = np.empty(features.shape[0], dtype=np.float64)
        stack: list[tuple[_Node, NDArray[np.intp]]] = [(self._root, np.arange(features.shape[0]))]
        while stack:
            node, rows = stack.pop()
            if node.left is None or node.right is None:
                out[rows] = node.value
                continue
            goes_left = features[rows, node.feature] <= node.threshold
            stack.append((node.left, rows[goes_left]))
            stack.append((node.right, rows[~goes_left]))
        return out


def _gini(positive_weight: float, total_weight: float) -> float:
    if total_weight <= 0:
        return 0.0
    p = positive_weight / total_weight
    return 2.0 * p * (1.0 - p)


def min_leaf_for(rows: int) -> int:
    """Smallest leaf the detector may create, given how much it has to train on.

    A fixed floor of 50 rows cannot split a sample of a hundred at all, so a detector run over a
    few hundred account events scored exactly 0.5 whatever was planted in them: it was blind rather
    than reassuring (M2 principal review). The floor now shrinks with the sample and is capped at
    the original value, so large runs behave as before.
    """
    return max(MIN_LEAF_FLOOR, min(MIN_LEAF_CAP, rows // LEAF_FRACTION))


def cross_validated_auc(
    features: Floats,
    labels: Bools,
    *,
    folds: int,
    seed: int,
    groups: NDArray[np.int64] | None = None,
) -> float:
    """Mean out-of-fold AUC of a :class:`ShallowTree`.

    Folds are stratified by label, or, when ``groups`` is given, formed from whole groups so that
    rows of one group (for example one account's fraud incident, whose rows share a time) never
    sit on both sides of a split.
    """
    rng = np.random.default_rng(seed)
    assignment = np.empty(labels.size, dtype=np.int64)
    if groups is not None:
        unique, inverse = np.unique(groups, return_inverse=True)
        assignment = rng.permutation(unique.size)[inverse] % folds
    else:
        for value in (True, False):
            members = np.flatnonzero(labels == value)
            rng.shuffle(members)
            assignment[members] = np.arange(members.size) % folds
    scores = []
    for fold in range(folds):
        train, test = assignment != fold, assignment == fold
        tree = ShallowTree(max_depth=DETECTOR_DEPTH, min_leaf=min_leaf_for(int(train.sum())))
        tree.fit(features[train], labels[train])
        scores.append(auc(tree.predict(features[test]), labels[test]))
    return float(np.mean(scores))
