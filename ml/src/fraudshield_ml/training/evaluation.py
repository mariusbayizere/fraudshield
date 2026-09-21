"""The first M4 metric: a model evaluated on D-07's published split (PB-49).

**What makes this different from the smoke test**, which stays exactly what it is. The smoke test
cut a 70/30 holdout of its own sample — *a* temporal split and not *the* one — so its figures were
not comparable with any gate. This reads the boundaries the dataset publishes, fits on the train
period, scores the test period, and never touches the embargo. A number from here is defined on
the same rows the gates are.

**What it still is not.** The corpus is bounded, so the training rows are the *tail* of the train
period rather than all of it, and the report says so beside every figure. Nothing is tuned and
nothing is calibrated: the validation and calibration periods are identified and deliberately
unused, because using them without reporting what was tuned would make the test figure optimistic
in a way no reader could detect.

**Every figure is a margin** over the strongest single feature measured on the same rows (PB-46).
A model AUC on this benchmark compared against 0.5 is wrong by roughly four times the quantity
that matters, because one velocity feature reaches 0.894 on its own.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from fraudshield_ml.training.split import Boundaries

#: The trivial rule's candidates: a threshold on the amount, or on the time of day. This mirrors
#: the dataset's own trivial-rule baseline ("amount >= rule threshold, or local hour before
#: 05:00", 0.604 in `dataset/realism_report.md`) using the quantities the feature pipeline has.
#: They are named rather than derived, because "trivial" is a claim about what a fraud team could
#: do without a model, and no property of a registry entry decides that. `seconds_since_last_tx`
#: is deliberately **not** here: it is a velocity feature, and calling a velocity feature trivial
#: is how a benchmark's hardest baseline gets reported as its easiest.
TRIVIAL_FEATURES = (
    "amount_log1p",
    "amount_zscore_90d",
    "amount_to_max_90d_ratio",
    "local_hour_sin",
    "local_hour_cos",
    "is_local_night",
)


@dataclass(frozen=True)
class SegmentCounts:
    """What each period contributed, so a thin one is visible rather than inferred."""

    name: str
    rows: int
    fraud: int

    @property
    def rate(self) -> float:
        return self.fraud / self.rows if self.rows else 0.0


@dataclass(frozen=True)
class Evaluation:
    """A model's figures on the published split, with everything needed to read them honestly."""

    features: int
    train: SegmentCounts
    test: SegmentCounts
    corpus_rows: int
    train_covers_days: float
    model_auc: float
    model_auc_error: float
    recall_at_1pct_fpr: float
    baseline_auc: float
    baseline_feature: str
    trivial_auc: float
    trivial_feature: str
    boundaries: Boundaries

    @property
    def margin(self) -> float:
        return self.model_auc - self.baseline_auc

    @property
    def trivial_margin(self) -> float:
        return self.model_auc - self.trivial_auc


def summarise(result: Evaluation) -> str:
    """The report. Both baselines are printed before the model's own number, by construction."""
    interval = 1.96 * result.model_auc_error
    lines = [
        "MODEL ON D-07's PUBLISHED SPLIT — fitted on the train period, scored on the test",
        "period, embargo excluded. Not tuned and not calibrated: the validation and calibration",
        "periods are identified and deliberately unused.",
        "",
        f"  split                        {result.boundaries.describe()}",
        f"  features trained on          {result.features} (the computable ones, never 44)",
        f"  corpus rows scanned          {result.corpus_rows}",
        f"  train rows / fraud           {result.train.rows} / {result.train.fraud} "
        f"({result.train.rate:.3%})",
        f"  test rows / fraud            {result.test.rows} / {result.test.fraud} "
        f"({result.test.rate:.3%})",
        "",
        f"  best single feature          {result.baseline_auc:.3f}  ({result.baseline_feature})",
        f"  best trivial rule            {result.trivial_auc:.3f}  ({result.trivial_feature})",
        f"  model AUC                    {result.model_auc:.3f} +/-{interval:.3f}",
        f"  MARGIN OVER THE FEATURE      {result.margin:+.3f}",
        f"  MARGIN OVER THE TRIVIAL RULE {result.trivial_margin:+.3f}",
        f"  recall at 1% FPR             {result.recall_at_1pct_fpr:.3f}",
        "",
        f"  TRAINED ON THE LAST {result.train_covers_days:.0f} DAYS of the train period, not all",
        "  of it: the corpus is bounded and the feature pass is its cost. A figure from here is",
        "  comparable with the gates in its split and not in its training volume.",
    ]
    if result.margin <= 0:
        lines.append("")
        lines.append(
            "  The model does not beat one threshold on one feature. On this benchmark that is"
        )
        lines.append("  the number to watch, not the AUC.")
    elif result.margin < interval:
        lines.append("")
        lines.append(
            "  The margin is inside the model's own interval, so this run does not establish"
        )
        lines.append("  that the model beats the single feature at all.")
    return "\n".join(lines)


def counts(labels: Sequence[bool]) -> tuple[int, int]:
    return len(labels), sum(1 for y in labels if y)
