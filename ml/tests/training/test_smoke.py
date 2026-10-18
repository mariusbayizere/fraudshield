"""The pipeline smoke test's arithmetic (E1, E3, D-08).

Three things are worth pinning here and the rest is scaffolding. The **floor** the report prints
must be the published one, or the margin is measured against a number nobody can trace. The
**encoding** must hold a training row's whole account out of its own estimate (E1), and it computes
that by subtracting each fold from the whole rather than by rescanning — a form that is fast and
easy to get subtly wrong, so it is held against an obvious implementation written independently
below. And the **report** must print the floor before the model's number, because the ordering is
the whole point of the gate.
"""

from __future__ import annotations

import math
import re
from collections.abc import Sequence
from pathlib import Path

import pytest

from fraudshield_ml.features.registry import REGISTRY, Computability, Dtype
from fraudshield_ml.features.vector import FeatureValue
from fraudshield_ml.training.smoke import (
    CACHE_ACCOUNT,
    CACHE_CHANNEL,
    CACHE_COUNTRY,
    CACHE_LABEL,
    CACHE_SEGMENT,
    CACHE_VARIANT,
    FLOOR_FEATURE,
    SINGLE_FEATURE_FLOOR,
    SmokeResult,
    cache_key,
    cache_read,
    cache_write,
    encode_categoricals,
    evaluate,
    fit_and_score,
    floor_from,
    recall_at_fpr,
    summarise,
    trainable_features,
)

REPO = Path(__file__).resolve().parents[3]
BASELINE_DOCUMENT = REPO / "docs/benchmarks/single_feature_baseline.md"


def obvious_encoding(
    values: Sequence[str],
    labels: Sequence[bool],
    accounts: Sequence[str],
    train: Sequence[int],
    *,
    prior_weight: float,
) -> list[float]:
    """E1 written the slow, obvious way: for every training row, rescan the rows before it.

    Independent of the implementation under test — it keeps no running totals and subtracts
    nothing — so agreement is evidence the one-pass form is right rather than a shared bug.
    """
    training = set(train)
    base = sum(1 for i in train if labels[i]) / max(len(train), 1)
    column = []
    for i in range(len(values)):
        if i not in training:
            fraud = sum(1.0 for j in train if values[j] == values[i] and labels[j])
            seen = sum(1.0 for j in train if values[j] == values[i])
            column.append((fraud + prior_weight * base) / (seen + prior_weight))
            continue
        earlier = [j for j in train if j < i and accounts[j] != accounts[i]]
        earlier_base = sum(1 for j in earlier if labels[j]) / len(earlier) if earlier else 0.0
        fraud = sum(1.0 for j in earlier if values[j] == values[i] and labels[j])
        seen = sum(1.0 for j in earlier if values[j] == values[i])
        column.append((fraud + prior_weight * earlier_base) / (seen + prior_weight))
    return column


def rows_from(channels: Sequence[str], corridors: Sequence[str]) -> list[dict[str, FeatureValue]]:
    return [
        {"channel": channel, "corridor_class": corridor}
        for channel, corridor in zip(channels, corridors, strict=True)
    ]


@pytest.mark.req("ML-DATA-01", "D-08")
def test_the_trainable_set_is_the_computable_set_and_is_not_forty_four() -> None:
    """Derived from the registry, so a declaration change moves the training set with it.

    The count is asserted against the registry rather than against 36, because a literal here
    would be a second place for the number to live and the two would drift. What *is* asserted
    literally is that it is smaller than the registry — the claim PB-44 exists to keep visible.
    """
    trainable = trainable_features()
    assert trainable == tuple(
        sorted(n for n, s in REGISTRY.items() if s.computable is Computability.COMPUTABLE)
    )
    assert len(trainable) < len(REGISTRY)
    assert sorted(trainable) == list(trainable)


@pytest.mark.req("D-08")
def test_the_floor_matches_the_published_baseline() -> None:
    """The constant in the module and the figure in the document are one number in two places.

    Precondition: the document exists and names the feature, so a renamed or deleted baseline
    fails here rather than leaving the constant unchecked.
    """
    text = BASELINE_DOCUMENT.read_text(encoding="utf-8")
    assert FLOOR_FEATURE in text
    quoted = re.search(rf"{re.escape(FLOOR_FEATURE)}\D{{0,80}}?(\d\.\d\d\d)", text)
    assert quoted is not None, "the baseline document does not state a figure for the floor feature"
    assert float(quoted.group(1)) == SINGLE_FEATURE_FLOOR


@pytest.mark.req("D-08", "ML-GATE-01")
def test_the_fast_encoding_matches_the_obvious_one() -> None:
    """Preconditions, so this cannot pass by both sides seeing nothing.

    The data is built so that the subtraction is exercised: accounts repeat, so a row's own
    account has earlier rows to remove, and the same category carries both labels. Without either
    the two forms would agree on data that never tests the part that is easy to get wrong.
    """
    accounts = [f"acct-{i % 7}" for i in range(24)]
    channels = ["mobile_money"] * 10 + ["card"] * 10 + ["agent"] * 4
    corridors = ["domestic"] * 12 + ["intra_bloc"] * 12
    labels = [i % 5 == 0 for i in range(24)]
    train = list(range(18))

    assert len({accounts[i] for i in train}) >= 2, "precondition: more than one account"
    per_category = {
        value: {labels[i] for i in train if channels[i] == value} for value in set(channels[:18])
    }
    assert any(len(seen) == 2 for seen in per_category.values()), (
        "precondition: some category must carry both labels, or the encoding is degenerate"
    )

    encoded = encode_categoricals(rows_from(channels, corridors), labels, accounts, train)
    assert set(encoded) == {n for n, s in REGISTRY.items() if s.dtype is Dtype.CATEGORICAL}
    for name, values in (("channel", channels), ("corridor_class", corridors)):
        expected = obvious_encoding(values, labels, accounts, train, prior_weight=50.0)
        assert encoded[name] == pytest.approx(expected, abs=1e-12)


@pytest.mark.req("D-08", "ML-GATE-01")
def test_a_training_row_is_scored_without_any_row_of_its_own_account() -> None:
    """E1's account rule, stated directly rather than inferred from agreement.

    Eight legitimate rows of other accounts come first; then one account's four fraud rows. Its
    last row has three earlier rows of its own, all fraud, in the same category. Excluded, the
    category is legitimate only and the estimate is zero; included, it would not be.
    """
    accounts = [f"other-{i}" for i in range(8)] + ["leaky"] * 4
    channels = ["agent"] * 12
    corridors = ["domestic"] * 12
    labels = [False] * 8 + [True] * 4
    train = list(range(12))

    encoded = encode_categoricals(rows_from(channels, corridors), labels, accounts, train)
    assert encoded["channel"][11] == pytest.approx(0.0, abs=1e-12), (
        "the account's own earlier fraud rows reached its estimate"
    )


@pytest.mark.req("D-08", "ML-GATE-01", "FR-02-03")
def test_a_later_training_row_never_informs_an_earlier_one() -> None:
    """E1's time rule: flipping every label after a row leaves that row's encoding unchanged."""
    accounts = [f"acct-{i % 9}" for i in range(40)]
    channels = ["card", "agent", "ussd", "card"] * 10
    corridors = ["domestic", "intra_bloc"] * 20
    labels = [i % 3 == 0 for i in range(40)]
    train = list(range(40))
    before = encode_categoricals(rows_from(channels, corridors), labels, accounts, train)
    flipped = labels[:20] + [not y for y in labels[20:]]
    after = encode_categoricals(rows_from(channels, corridors), flipped, accounts, train)
    for name in ("channel", "corridor_class"):
        assert after[name][:21] == pytest.approx(before[name][:21], abs=1e-12)
        assert after[name][21:] != pytest.approx(before[name][21:], abs=1e-12), (
            "precondition: the flip must move something, or this test proves nothing"
        )


@pytest.mark.req("D-08", "ML-GATE-01")
def test_a_test_row_is_encoded_from_the_training_rows_only() -> None:
    """A held-out row's own label must not reach its own score, and its neighbours' must not either.

    Two test rows share a category that is pure fraud among the *test* rows and pure legitimate
    among the training rows. Encoded from training only, both read low; encoded from everything,
    both would read high.
    """
    accounts = [f"acct-{i}" for i in range(10)]
    channels = ["card"] * 10
    corridors = ["domestic"] * 10
    labels = [False] * 8 + [True, True]
    train = list(range(8))
    assert not any(labels[i] for i in train), "precondition: training rows carry no fraud here"

    encoded = encode_categoricals(rows_from(channels, corridors), labels, accounts, train)
    assert encoded["channel"][8] == pytest.approx(0.0, abs=1e-12)
    assert encoded["channel"][9] == pytest.approx(0.0, abs=1e-12)


@pytest.mark.req("ML-GATE-03")
def test_recall_at_a_false_positive_rate_reads_the_threshold_off_the_negatives() -> None:
    """Hand-worked. Ten negatives at 0.0-0.9 and two positives at 0.95 and 0.05.

    At 10% FPR the threshold is the highest negative, 0.9: one positive clears it, so recall is
    0.5. A threshold read off *all* the scores instead would sit lower and report 1.0.
    """
    negatives = [i / 10 for i in range(10)]
    scores = [*negatives, 0.95, 0.05]
    labels = [False] * 10 + [True, True]
    assert recall_at_fpr(scores, labels, 0.1) == pytest.approx(0.5)
    assert math.isnan(recall_at_fpr([0.1, 0.2], [False, False], 0.1)), "no positives, no recall"
    assert math.isnan(recall_at_fpr([0.1, 0.2], [True, True], 0.1)), "no negatives, no threshold"


@pytest.mark.req("D-08")
def test_the_floor_is_measured_on_the_same_rows_it_is_subtracted_from() -> None:
    """`floor_from` takes max(AUC, 1-AUC): a feature that predicts the negative class is still a
    baseline a model has to beat, because its sign is free to choose.
    """
    scores = [1.0, 2.0, 3.0, 4.0]
    assert floor_from(scores, [True, True, False, False]) == 1.0
    assert floor_from(scores, [False, False, True, True]) == 1.0


@pytest.mark.req("D-08")
def test_the_report_prints_the_floor_before_the_model_and_calls_itself_not_a_result() -> None:
    """The gate PB-46 asks for, as text: no model number without its baseline above it."""
    report = summarise(
        SmokeResult(
            features=36,
            train_rows=7000,
            test_rows=3000,
            train_fraud=40,
            test_fraud=18,
            model_auc=0.930,
            model_auc_error=0.020,
            recall_at_1pct_fpr=0.44,
            baseline_auc=0.894,
            baseline_feature=FLOOR_FEATURE,
        )
    )
    assert report.startswith("PIPELINE SMOKE TEST — not a result")
    assert report.index("single-feature floor") < report.index("model AUC")
    assert "+0.036" in report
    assert "36 (the computable ones, never 44)" in report
    assert "44 features" not in report


@pytest.mark.req("D-08")
def test_the_report_says_so_when_the_margin_does_not_clear_the_interval() -> None:
    """A margin of +0.010 against an interval of +/-0.039 establishes nothing, and the report has
    to say that rather than leaving a positive sign to speak for itself.
    """
    beaten = SmokeResult(
        features=36,
        train_rows=7000,
        test_rows=3000,
        train_fraud=40,
        test_fraud=18,
        model_auc=0.904,
        model_auc_error=0.020,
        recall_at_1pct_fpr=0.44,
        baseline_auc=0.894,
        baseline_feature=FLOOR_FEATURE,
    )
    assert "does not establish" in summarise(beaten)
    lost = SmokeResult(**{**beaten.__dict__, "model_auc": 0.850})
    assert lost.margin < 0
    assert "does not beat one threshold" in summarise(lost)


@pytest.mark.req("D-08")
def test_evaluate_reports_an_interval_that_widens_as_the_positives_thin() -> None:
    """Twenty positives against a thousand negatives must carry a wider interval than two hundred
    do at the same AUC — the reason the smoke report prints one at all.
    """
    # The positives overlap the negatives on purpose. A perfectly separated sample has a
    # Hanley-McNeil variance of exactly zero at any n, so it would compare 0.0 with 0.0 and pass
    # whatever the implementation did.
    few = evaluate([0.9] * 10 + [0.1] * 10 + [0.1] * 1000, [True] * 20 + [False] * 1000)
    many = evaluate([0.9] * 100 + [0.1] * 100 + [0.1] * 1000, [True] * 200 + [False] * 1000)
    assert 0.5 < few[0] < 1.0, "precondition: an imperfect AUC, or the variance is zero by identity"
    assert few[0] == pytest.approx(many[0]), "precondition: the same AUC, so only n differs"
    assert few[1] > many[1]


@pytest.mark.req("ML-GATE-01")
def test_the_model_half_runs_without_scikit_learn_and_learns_a_planted_signal() -> None:
    """The test that would have saved the first run.

    `XGBClassifier` imports scikit-learn, which this package does not depend on, and the first
    smoke attempt discovered that *after* eleven minutes of feature computation. The check is
    cheap: one column carries the label, one is noise, and the booster must rank the held-out
    positives above the negatives. A model that trains and predicts nothing useful would still
    catch the import; the planted signal is here so a silent scoring bug does not pass as well.
    """
    matrix = [[float(i % 2), float(i % 7)] for i in range(200)]
    labels = [i % 2 == 1 for i in range(200)]
    train, test = list(range(140)), list(range(140, 200))
    held_out = [labels[i] for i in test]
    assert any(held_out), "precondition: the holdout must hold a positive"
    assert not all(held_out), "precondition: the holdout must hold a negative"

    scores = fit_and_score(matrix, labels, train, test, seed=1)
    assert len(scores) == len(test)
    assert all(0.0 <= s <= 1.0 for s in scores), "binary:logistic must return probabilities"
    assert evaluate(scores, [labels[i] for i in test])[0] == pytest.approx(1.0)


@pytest.mark.req("ML-GATE-01")
def test_a_structural_nan_column_is_passed_through_rather_than_rejected() -> None:
    """D-04: whole channels are NaN together, and the booster must split on that rather than fail.

    Columns of pure NaN are the shape `accounts_per_device_7d` and the five source-data gaps have
    on this benchmark, so the model half has to accept them.
    """
    matrix = [[float(i % 2), math.nan] for i in range(120)]
    labels = [i % 2 == 1 for i in range(120)]
    scores = fit_and_score(matrix, labels, list(range(80)), list(range(80, 120)), seed=1)
    assert all(not math.isnan(s) for s in scores)


@pytest.mark.req("ML-DATA-01")
def test_the_cache_is_reused_only_under_the_settings_it_was_written_for(tmp_path: Path) -> None:
    """A cache keyed on nothing reports one run's numbers under another run's settings.

    Each field of the key is changed in turn and must invalidate the cache on its own — a key
    checked only in aggregate would pass while ignoring one of its parts.
    """
    names = trainable_features()
    rows: list[dict[str, FeatureValue]] = [
        {name: ("card" if name in {"channel", "corridor_class"} else float(i)) for name in names}
        for i in range(4)
    ]
    extras: dict[str, list[object]] = {
        CACHE_LABEL: ["True", "False", "True", "False"],
        CACHE_ACCOUNT: ["a", "b", "c", "d"],
        CACHE_SEGMENT: ["train", "train", "test", "calibration"],
        CACHE_COUNTRY: ["AA", "AA", "BB", "CC"],
        CACHE_CHANNEL: ["USSD", "CARD", "AGENT_BANKING", "USSD"],
        CACHE_VARIANT: ["base", "", "novel_esim_delayed_drain", ""],
    }
    key = cache_key("/data/bench", 1000, 4)
    path = tmp_path / "matrix.parquet"

    assert cache_read(path, key) is None, "an absent cache must not be a match"
    cache_write(path, key, rows, extras)
    got = cache_read(path, key)
    assert got is not None
    assert got[1] == {k: [str(v) for v in vs] for k, vs in extras.items()}
    assert got[0][2]["velocity_ratio_1h_vs_30d"] == rows[2]["velocity_ratio_1h_vs_30d"]

    with pytest.raises(ValueError, match="alongside the features"):
        cache_write(tmp_path / "partial.parquet", key, rows, {CACHE_LABEL: ["True"] * 4})

    assert cache_read(path, cache_key("/data/other", 1000, 4)) is None
    assert cache_read(path, cache_key("/data/bench", 2000, 4)) is None
    assert cache_read(path, cache_key("/data/bench", 1000, 8)) is None
    assert cache_read(path, {**key, "features": "one,two"}) is None
