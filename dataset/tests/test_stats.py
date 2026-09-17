from __future__ import annotations

import numpy as np
import pytest

from fraudshield_dataset.realism.stats import ShallowTree, auc, cross_validated_auc, separation

pytestmark = pytest.mark.req("D-08")


def test_auc_matches_hand_computed_cases() -> None:
    labels = np.array([False, False, True, True])
    assert auc(np.array([0.1, 0.4, 0.35, 0.8]), labels) == pytest.approx(0.75)
    assert auc(np.array([1.0, 2.0, 3.0, 4.0]), labels) == 1.0
    assert auc(np.array([4.0, 3.0, 2.0, 1.0]), labels) == 0.0
    assert auc(np.array([1.0, 1.0, 1.0, 1.0]), labels) == 0.5  # ties count half
    assert auc(np.array([1.0, 2.0]), np.array([True, True])) == 0.5  # undefined
    assert separation(np.array([4.0, 3.0, 2.0, 1.0]), labels) == 1.0


def test_tree_finds_a_planted_split_and_cross_validation_reports_it() -> None:
    rng = np.random.default_rng(0)
    x = rng.normal(size=(4000, 3))
    y = (x[:, 1] > 0.8) & (rng.random(4000) < 0.9)
    tree = ShallowTree(max_depth=2).fit(x, y)
    assert auc(tree.predict(x), y) > 0.9
    assert cross_validated_auc(x, y, folds=3, seed=1) > 0.9


def test_tree_on_noise_scores_near_one_half() -> None:
    rng = np.random.default_rng(1)
    x = rng.normal(size=(6000, 4))
    y = rng.random(6000) < 0.05
    assert abs(cross_validated_auc(x, y, folds=4, seed=2) - 0.5) < 0.05


def test_tree_edge_cases() -> None:
    with pytest.raises(RuntimeError, match="not fitted"):
        ShallowTree().predict(np.zeros((1, 1)))
    pure = ShallowTree().fit(np.zeros((200, 1)), np.zeros(200, dtype=bool))
    assert pure.predict(np.zeros((2, 1))).tolist() == [0.0, 0.0]
    constant = ShallowTree(min_leaf=10).fit(np.zeros((200, 1)), np.arange(200) % 2 == 0)
    assert constant.predict(np.zeros((1, 1)))[0] == pytest.approx(0.5)


def test_grouped_folds_keep_each_group_on_one_side() -> None:
    rng = np.random.default_rng(4)
    # Clustered labels: each group's rows share a feature value and a label.
    groups = np.repeat(np.arange(400), 5)
    x = np.repeat(rng.random(400), 5).reshape(-1, 1)
    y = np.repeat(rng.random(400) < 0.2, 5)
    ungrouped = cross_validated_auc(x, y, folds=5, seed=1)
    grouped = cross_validated_auc(x, y, folds=5, seed=1, groups=groups)
    assert ungrouped > 0.6  # clusters leak across folds
    assert abs(grouped - 0.5) < 0.06
