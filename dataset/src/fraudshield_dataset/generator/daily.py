"""Which days of a month a customer is active on, shared by behaviour and account events.

Both legitimate behaviour and the account events that fraud needs (a SIM swap, a device change)
have to place themselves on a day of the month, and they have to do it the *same* way. When they
did not, the difference was a label: fraud-planted events fell on any day of a real month while
legitimate ones were confined to days 1 to 27, so the day of the month alone identified a victim's
account (found in the M2 principal review).
"""

from __future__ import annotations

import calendar
import datetime as dt
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Rhythm:
    """The parameters that shape a month: payday window and boost, market-day weight."""

    payday_window: int
    payday_boost: float
    market_weight: float


def day_weights(
    *,
    segment: str,
    salary_day: int,
    market_weekday: int,
    month: tuple[int, int],
    rhythm: Rhythm,
) -> np.ndarray:
    """Probability of each day of the month for one customer, summing to one.

    Salaried customers spend in the days after payday; traders on their market weekday. Everyone
    else is flat across the month.
    """
    year, month_number = month
    days = calendar.monthrange(year, month_number)[1]
    weights = np.ones(days)
    if segment == "urban_salaried":
        start = min(salary_day, days)
        weights[start - 1 : min(days, start - 1 + rhythm.payday_window)] *= rhythm.payday_boost
    if segment == "informal_trader":
        for day in range(1, days + 1):
            if dt.date(year, month_number, day).weekday() == market_weekday:
                weights[day - 1] *= rhythm.market_weight
    return weights / weights.sum()


def month_parts(label: str) -> tuple[int, int]:
    """``("2024-07")`` to ``(2024, 7)``."""
    return int(label[:4]), int(label[5:])
