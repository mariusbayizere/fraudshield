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
class Baseline:
    """A single feature's separation on the held-out rows, **with the rows it was measured on**.

    The coverage is not decoration. `auc` drops NaN scores with their labels, which is right — a
    structurally missing value is not a low value — but it means a feature defined on a tenth of
    the rows is scored on a tenth of the rows, while the model is scored on all of them. Reporting
    the two as a margin without saying so compares numbers over different populations.

    The first run of this command did exactly that and produced a headline of -0.002 against
    `days_since_sim_swap` at 1.000, which is defined only on the accounts that had a SIM swap.
    """

    feature: str
    separation: float
    rows: int
    fraud: int
    covered: float

    @property
    def partial(self) -> bool:
        """Whether the feature was scored on fewer rows than the model."""
        return self.covered < 1.0


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
    #: The strongest single feature defined on **every** held-out row, so the margin below
    #: compares two numbers over one population.
    baseline: Baseline
    #: The strongest single feature of any coverage. When it is a partial one this is the more
    #: interesting number and the less comparable one, so it is reported separately rather than
    #: substituted for the baseline.
    strongest: Baseline
    trivial: Baseline
    boundaries: Boundaries

    @property
    def margin(self) -> float:
        return self.model_auc - self.baseline.separation

    @property
    def trivial_margin(self) -> float:
        return self.model_auc - self.trivial.separation


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
        f"  best single feature          {result.baseline.separation:.3f}  "
        f"({result.baseline.feature}, defined on every row)",
        f"  best trivial rule            {result.trivial.separation:.3f}  "
        f"({result.trivial.feature})",
        f"  model AUC                    {result.model_auc:.3f} +/-{interval:.3f}",
        f"  MARGIN OVER THE FEATURE      {result.margin:+.3f}",
        f"  MARGIN OVER THE TRIVIAL RULE {result.trivial_margin:+.3f}",
        f"  recall at 1% FPR             {result.recall_at_1pct_fpr:.3f}",
        "",
        f"  strongest feature of any     {result.strongest.separation:.3f}  "
        f"({result.strongest.feature})",
        f"  ...defined on                {result.strongest.covered:.1%} of held-out rows "
        f"({result.strongest.rows} rows, {result.strongest.fraud} fraud)",
        "",
    ]
    if result.strongest.partial:
        lines += [
            "  THE STRONGEST FEATURE IS NOT THE BASELINE, and the difference is the point. It is",
            "  scored only on the rows where it is defined, so its figure and the model's are over",
            "  different populations and the two cannot be subtracted. It is printed because a",
            "  benchmark on which one partial feature separates that well is a fact about the",
            "  benchmark, not a detail of this run.",
        ]
    lines += [
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


def spread(pool: Sequence[int], wanted: int) -> list[int]:
    """`wanted` rows spread evenly across `pool`, in order.

    A sampling decision and therefore part of the evaluation's design, not a CLI detail. Evenly
    rather than randomly: the pool is already in timestamp order, so a stride is a stratified
    sample over time by construction and needs no seed to be reproducible.

    The first two runs of this command took the pool's **tail** and both trained on about a week,
    at 400,000 and at 560,000 corpus rows — the sample size decided the window and the corpus
    depth did nothing. A stride costs the same and covers the period.
    """
    if wanted >= len(pool):
        return list(pool)
    stride = len(pool) / wanted
    return [pool[int(i * stride)] for i in range(wanted)]


def counts(labels: Sequence[bool]) -> tuple[int, int]:
    return len(labels), sum(1 for y in labels if y)
