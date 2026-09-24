"""The model's default risk thresholds, read from the Redis config store (FR-02-06).

**What these thresholds are for.** The scorer returns `model_risk_tier`, "the tier implied by the
model's default thresholds"; the API recomputes the tier from each institution's per-channel
thresholds before deciding (FR-05-07). So these are the defaults, and changing them never reloads
a model: the scorer re-reads one Redis hash at most every `refresh` seconds, well inside
FR-02-06's 60 seconds.

The hash is written by the admin API (`PATCH /api/v1/admin/thresholds`, M7), under ADR 0014's dual
control; this module only reads it. A missing hash, an unreadable Redis or an invalid set of values
keeps the last good thresholds (initially the specified defaults) rather than failing the score:
thresholds are configuration, and a configuration outage must not become a scoring outage.

**The anomaly threshold (D-06).** FR-02-05 routes `anomaly_score > 0.7` to review whatever the
ensemble says. D-06 keeps 0.7 as the test-profile default and makes production a configurable
percentile (default 0.995) sized by the alert budget; both profiles are here.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from fraudshield_ml.serving.generated import scoring_pb2 as pb

KEY = "fs:config:model_thresholds"
REFRESH_SECONDS = 10.0
LOG = logging.getLogger(__name__)


@dataclass(frozen=True)
class Thresholds:
    medium: float = 0.60
    high: float = 0.85
    #: D-06: the test profile's 0.7. Production sets the percentile from the alert budget.
    anomaly_review: float = 0.70

    def __post_init__(self) -> None:
        if not 0.0 < self.medium < self.high <= 1.0:
            raise ValueError(f"need 0 < medium < high <= 1, got {self.medium}, {self.high}")
        if not 0.0 < self.anomaly_review <= 1.0:
            raise ValueError(f"anomaly_review must be in (0, 1], got {self.anomaly_review}")

    def tier(self, ensemble_score: float, anomaly_score: float) -> int:
        """HIGH and MEDIUM from the ensemble; an anomaly above the review threshold is at least
        MEDIUM, so it reaches an analyst "regardless of ensemble_score" (FR-02-05)."""
        if ensemble_score >= self.high:
            return pb.RISK_TIER_HIGH
        if ensemble_score >= self.medium or anomaly_score > self.anomaly_review:
            return pb.RISK_TIER_MEDIUM
        return pb.RISK_TIER_LOW


DEFAULT = Thresholds()
PRODUCTION = Thresholds(anomaly_review=0.995)


class ThresholdStore:
    """Thresholds re-read from Redis at most every `refresh` seconds, on the caller's thread.

    Reading on the caller's thread rather than a timer thread keeps a scoring process free of
    background I/O it does not need; the read is one HGETALL, done once per interval.
    """

    def __init__(
        self,
        redis: Any | None,
        *,
        default: Thresholds = DEFAULT,
        refresh: float = REFRESH_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if refresh > 60.0:
            raise ValueError("FR-02-06 requires a change to take effect within 60 seconds")
        self._redis = redis
        self._current = default
        self._refresh = refresh
        self._clock = clock
        self._checked = -float("inf")
        self._lock = threading.Lock()

    def current(self) -> Thresholds:
        now = self._clock()
        if self._redis is None or now - self._checked < self._refresh:
            return self._current
        with self._lock:
            if now - self._checked >= self._refresh:
                self._checked = now
                self._current = self._read() or self._current
        return self._current

    def _read(self) -> Thresholds | None:
        assert self._redis is not None  # noqa: S101 - checked by the caller
        try:
            raw = self._redis.hgetall(KEY)
        except Exception:
            LOG.warning("threshold store unreadable; keeping the last good thresholds")
            return None
        if not raw:
            return None
        try:
            return Thresholds(
                medium=float(raw.get("medium", self._current.medium)),
                high=float(raw.get("high", self._current.high)),
                anomaly_review=float(raw.get("anomaly_review", self._current.anomaly_review)),
            )
        except ValueError as error:
            LOG.warning("invalid thresholds in %s ignored: %s", KEY, error)
            return None
