"""Rank-based AUC and a small depth-limited decision tree, in NumPy (ADR 0022 section 7).

Only what the checks need: the single-feature AUC limit and the shortcut detector. Kept small and
tested against hand-computed cases, so the checks do not depend on a machine-learning library.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

DETECTOR_DEPTH = 3
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
        tree = ShallowTree(max_depth=DETECTOR_DEPTH).fit(features[train], labels[train])
        scores.append(auc(tree.predict(features[test]), labels[test]))
    return float(np.mean(scores))
