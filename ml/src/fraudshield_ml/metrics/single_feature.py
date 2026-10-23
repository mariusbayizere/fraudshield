"""Single-feature AUC with account-grouped out-of-fold encoding (M3 exit criteria E3 and E6).

D-08 caps any single feature at **0.80**: a benchmark where one column separates the classes is a
benchmark that measures that column rather than a detector. M2 checked the dataset's raw columns;
E3 requires the same check over the **44 engineered features**, which can separate the classes far
better than the columns they are built from.

Two rules from M2 are built in rather than left to the caller, because both were learned by
getting them wrong:

* **Target encoding folds by whole accounts, never by row** (E1). M-8 removed a row's own label
  from its own score and then assigned folds per row, so the other rows of the same fraud incident
  stayed inside the estimate that scored it. That inflated `merchant_category_code` by 0.005 and
  `channel` by 0.006 before it was found.
* **Every figure is reported with the scale it was measured at** (E2). Single-feature AUC on this
  benchmark depends on dataset size by more than seed noise, and the cause is unexplained, so a
  number without its scale is not admissible as evidence.

`max(auc, 1 - auc)` throughout: a feature that predicts the negative class perfectly is exactly as
much of a shortcut as one that predicts the positive class, and reporting 0.05 as "well under the
ceiling" is how that gets missed.
"""

from __future__ import annotations

import math
from collections.abc import Sequence

#: Part E.2's ceiling, repeated here rather than imported from the dataset package: the two check
#: the same constant for different things, and a shared import would make ml depend on the
#: generator for a number that is a requirement rather than a parameter.
SINGLE_FEATURE_AUC_LIMIT = 0.80


def auc(scores: Sequence[float], labels: Sequence[bool]) -> float:
    """Rank-based AUC, with ties taking their mean rank.

    Ties matter here and are not a detail: most of these features are counts, flags and
    saturating ratios, so a run of identical values is the normal case rather than an edge one.
    Treating ties as wins would report a constant feature at 1.0.

    NaN scores are **dropped with their labels**, and the caller is told how many survived —
    scoring a NaN as zero would rank every structurally-missing row together at one end, which is
    a shortcut the feature does not have.
    """
    pairs = [(s, bool(t)) for s, t in zip(scores, labels, strict=True) if not math.isnan(s)]
    positives = sum(1 for _, label in pairs if label)
    negatives = len(pairs) - positives
    if positives == 0 or negatives == 0:
        return math.nan

    pairs.sort(key=lambda pair: pair[0])
    ranks = [0.0] * len(pairs)
    i = 0
    while i < len(pairs):
        j = i
        while j + 1 < len(pairs) and pairs[j + 1][0] == pairs[i][0]:
            j += 1
        mean_rank = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[k] = mean_rank
        i = j + 1

    positive_rank_sum = sum(rank for rank, (_, label) in zip(ranks, pairs, strict=True) if label)
    return (positive_rank_sum - positives * (positives + 1) / 2.0) / (positives * negatives)


def separation(scores: Sequence[float], labels: Sequence[bool]) -> float:
    """`max(auc, 1 - auc)`: how much the feature separates the classes, in either direction."""
    value = auc(scores, labels)
    return value if math.isnan(value) else max(value, 1.0 - value)


def out_of_fold_target_encoding(
    categories: Sequence[str],
    labels: Sequence[bool],
    accounts: Sequence[str],
    *,
    folds: int = 5,
    prior_weight: float = 50.0,
) -> list[float]:
    """Encode a categorical by its fraud rate, estimated from the other folds only.

    **Folds are whole accounts** (E1). Assigning them per row leaves the other rows of the same
    fraud incident inside the estimate that scores it, which is M-8 — a defect that looked like a
    strong feature. The account is hashed to a fold so the assignment is deterministic and
    independent of row order, which `test_e4` relies on.

    Smoothed toward the global rate of the *training* folds with a pseudo-count, so a category
    seen twice does not score 0 or 1 on the strength of two rows. The prior is computed per fold,
    from that fold's complement, never from all rows: a prior fitted on everything leaks the
    held-out labels into every cell.
    """
    assignment = {account: hash_fold(account, folds) for account in set(accounts)}
    encoded = [0.0] * len(categories)
    for fold in range(folds):
        training = [i for i, account in enumerate(accounts) if assignment[account] != fold]
        if not training:
            continue
        base_rate = sum(1 for i in training if labels[i]) / len(training)
        totals: dict[str, list[float]] = {}
        for i in training:
            cell = totals.setdefault(categories[i], [0.0, 0.0])
            cell[0] += float(labels[i])
            cell[1] += 1.0
        for i, account in enumerate(accounts):
            if assignment[account] != fold:
                continue
            fraud, seen = totals.get(categories[i], [0.0, 0.0])
            encoded[i] = (fraud + prior_weight * base_rate) / (seen + prior_weight)
    return encoded


def hash_fold(account: str, folds: int) -> int:
    """A deterministic fold for an account.

    Python's `hash()` is salted per process, so it would make fold assignment differ between runs
    and quietly break E4's determinism. A stable digest of the account token is used instead.
    """
    digest = 0
    for byte in account.encode("utf-8"):
        digest = (digest * 131 + byte) % 2_147_483_647
    return digest % folds


def auc_standard_error(value: float, positives: int, negatives: int) -> float:
    """Hanley and McNeil's standard error for an AUC, from the class counts.

    A ceiling check without one is a number pretending to be a decision. At this benchmark's fraud
    rate a twenty-thousand-row sample holds about 170 positives, and the 95% interval around 0.80
    is then roughly +/- 0.04 — so a feature measured at 0.78 could be 0.82, and reporting "under
    the ceiling" without saying so would be asserting a precision the sample does not have.

    The exponential approximation for Q1 and Q2 is Hanley and McNeil's own, and it is conservative
    in the region that matters here: it assumes a smooth score distribution, while many of these
    features are counts and flags whose ties make the effective sample smaller, not larger.
    """
    if positives < 2 or negatives < 2 or math.isnan(value):
        return math.nan
    q1 = value / (2.0 - value)
    q2 = 2.0 * value * value / (1.0 + value)
    variance = (
        value * (1.0 - value)
        + (positives - 1) * (q1 - value * value)
        + (negatives - 1) * (q2 - value * value)
    ) / (positives * negatives)
    return math.sqrt(max(variance, 0.0))


def class_counts(scores: Sequence[float], labels: Sequence[bool]) -> tuple[int, int]:
    """Positives and negatives among the rows the feature actually scored.

    NaN rows are dropped by `auc`, so the counts that matter for its uncertainty are the counts
    after dropping — a feature NaN on four rows in five is measured on a fifth of the sample and
    its interval is correspondingly wider, which is exactly the thing a bare AUC hides.
    """
    usable = [bool(t) for s, t in zip(scores, labels, strict=True) if not math.isnan(s)]
    positives = sum(1 for label in usable if label)
    return positives, len(usable) - positives
