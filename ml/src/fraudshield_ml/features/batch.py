"""The batch feature path: whole histories, filtered.

Independent of `fraudshield_ml.features.online` — neither imports the other, and
`test_the_two_paths_do_not_import_each_other` walks the import graph to prove it. Both import
`registry` (declarative) and `types` (data), neither of which computes anything.

This path reads an account's history as a sequence and selects windows by filtering. The online
path accumulates the same quantities incrementally. They are written to look different on purpose:
a parity test is worth exactly the size of the surface the two do not share.

**Window bounds, which both paths must agree on** (parity mutation 2 exists to catch a divergence):
for a transaction scored at time `t`, the history considered is the half-open interval
`(t - 30d, t)`, partitioned exactly as `(t - 30d, t - 1h]` and `(t - 1h, t)`. The scored
transaction is never in its own window (`self_inclusion=EXCLUDED`), and a transaction landing
exactly on `t - 1h` belongs to the long window, so the partition has no gap and no overlap.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta

from fraudshield_ml.features.primitives import h3_cell
from fraudshield_ml.features.registry import smoothing_for
from fraudshield_ml.features.types import Outcome, Transaction

SHORT_WINDOW = timedelta(hours=1)
LONG_WINDOW = timedelta(days=30)
CELL_WINDOW = timedelta(days=30)


def velocity_ratio_1h_vs_30d(
    history: Sequence[Transaction],
    scored: Transaction,
    first_seen_at: datetime,
) -> float:
    """Trailing-hour count over the mean hourly count of the prior 30 days, both smoothed.

    `first_seen_at` is the durable field (`history_requirement=DURABLE`): the denominator divides
    by history **actually observed**, capped at 30 days (`history_basis=OBSERVED_CAPPED`), so a
    three-day-old account is not scored as though it had been quiet for 27 days.

    The 1 h numerator is removed from the 30 d denominator (`nesting=SHORT_EXCLUDED`), so the
    baseline does not move with the burst it is a baseline for.

    Equal smoothing on both terms with prior 1.0 makes a zero-history account return exactly 1.0 —
    "this account looks like its own baseline" — rather than 0.0 or NaN.
    """
    alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha

    t = scored.timestamp
    short_start, long_start = t - SHORT_WINDOW, t - LONG_WINDOW

    short_count = sum(1 for x in history if short_start < x.timestamp < t)
    long_count = sum(1 for x in history if long_start < x.timestamp <= short_start)

    observed = min(LONG_WINDOW, t - first_seen_at)
    baseline_hours = observed.total_seconds() / 3600.0 - SHORT_WINDOW.total_seconds() / 3600.0
    long_mean = long_count / baseline_hours if baseline_hours > 0 else 0.0

    return (short_count + alpha) / (long_mean + alpha)


def geo_cell_fraud_rate_30d(
    corpus: Sequence[Transaction],
    outcomes: dict[str, Outcome],
    scored: Transaction,
    prior: float,
) -> float:
    """Confirmed-fraud proportion of the scored transaction's H3 cell over the prior 30 days.

    Keyed by the **cell**, not the account (`history_key=GEO_CELL`), so E1's account-grouped folds
    do not isolate it: `corpus` must therefore already be restricted to training-fold rows, and
    `prior` fitted on the same rows. Passing the whole corpus is the mutation that would expose the
    leak, by raising the single-feature AUC above its clean value.

    Only outcomes whose `available_at` precedes the scored timestamp are counted
    (`label_basis=AVAILABLE_AT_LAG`) — never `confirmed_at`, which would import the investigation
    delay straight into the feature.
    """
    alpha = smoothing_for("geo_cell_fraud_rate_30d").alpha

    t = scored.timestamp
    start = t - CELL_WINDOW
    cell = h3_cell(scored.latitude, scored.longitude)

    total = 0
    fraud = 0
    for row in corpus:
        if row.transaction_id == scored.transaction_id:
            continue  # self_inclusion=EXCLUDED
        if not (start < row.timestamp < t):
            continue
        if h3_cell(row.latitude, row.longitude) != cell:
            continue
        outcome = outcomes.get(row.transaction_id)
        if outcome is None or outcome.available_at >= t:
            continue  # the label had not arrived; the online path could not have seen it either
        total += 1
        fraud += int(outcome.is_fraud)

    return (fraud + alpha * prior) / (total + alpha)
