"""One transaction in, one `ScoringResult` out: the scoring hot path (C.2 steps 5 to 8, FR-02-01).

Stages, each timed into `stage_timings`:

1. **Features**: the 44 from the transaction and its `AccountContext` (`serving.features`).
2. **Ensemble**: each booster's raw probability.
3. **Calibration**: the three isotonic calibrators (D-05).
4. **Isolation Forest**: `anomaly_score` and `anomaly_raw` (D-06).
5. **SHAP**: exact TreeSHAP, only when `ensemble_score >= 0.60` (FR-02-04), all 44 contributions
   plus the top five by absolute value.

A `Scorer` holds exactly one bundle and never changes it; hot swap replaces the whole `Scorer`
(`ModelHolder`), so a request that started on one model finishes on it and every result names the
model that produced it (FR-02-10).
"""

from __future__ import annotations

import math
import threading
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.models.bundle import SHAP_THRESHOLD, Bundle
from fraudshield_ml.serving import features
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.thresholds import Thresholds, ThresholdStore

Stage = pb.StageTiming.Stage
TOP = 5


def template_key(feature: str, increases: bool) -> str:
    """D-45's deterministic plain-language template key, in the OpenAPI contract's pattern."""
    return f"feature.{feature}.{'increases_risk' if increases else 'decreases_risk'}"


def feature_value(value: features.FeatureValue) -> pb.FeatureValue:
    if isinstance(value, str):
        return pb.FeatureValue(category=value)
    if math.isnan(value):
        return pb.FeatureValue(missing=pb.FeatureValue.Missing())
    return pb.FeatureValue(number=value)


@dataclass(frozen=True)
class Scored:
    """A result plus what the shadow path needs without re-deriving it."""

    result: pb.ScoringResult
    values: Mapping[str, features.FeatureValue]
    #: The feature paths' record of the transaction, for the store writer.
    transaction: Transaction


class Scorer:
    def __init__(
        self, bundle: Bundle, reference: Reference, thresholds: ThresholdStore | None = None
    ) -> None:
        self.bundle = bundle
        self.reference = reference
        self.thresholds = thresholds or ThresholdStore(None)

    @property
    def model_version(self) -> str:
        return self.bundle.model_version

    def score(self, request: pb.ScoreRequest) -> Scored:
        started = time.perf_counter()
        timings: list[tuple[int, float]] = []

        def lap(stage: int, since: float) -> float:
            now = time.perf_counter()
            timings.append((stage, (now - since) * 1000.0))
            return now

        tx = features.domain_transaction(request.transaction, self.reference)
        values = features.compute(
            tx, request.context, list(request.configured_limits), self.reference
        )
        mark = lap(Stage.STAGE_FEATURES, started)

        row = self.bundle.row(values)
        raw = self.bundle.raw(row)
        mark = lap(Stage.STAGE_ENSEMBLE, mark)
        prediction = self.bundle.calibrate(*raw)
        mark = lap(Stage.STAGE_CALIBRATION, mark)

        anomaly_score, anomaly_raw = self.bundle.anomaly(row)
        mark = lap(Stage.STAGE_ISOLATION_FOREST, mark)

        thresholds: Thresholds = self.thresholds.current()
        result = pb.ScoringResult(
            scoring_result_id=str(uuid.uuid4()),
            transaction_id=tx.transaction_id,
            ensemble_score=prediction.ensemble_score,
            xgboost_score=prediction.xgboost_score,
            lightgbm_score=prediction.lightgbm_score,
            anomaly_score=anomaly_score,
            anomaly_raw=anomaly_raw,
            model_risk_tier=thresholds.tier(prediction.ensemble_score, anomaly_score),  # type: ignore[arg-type]
            model_version=self.bundle.model_version,
            feature_registry_version=self.bundle.registry_version,
        )
        for name, value in values.items():
            result.feature_vector[name].CopyFrom(feature_value(value))

        if prediction.ensemble_score >= SHAP_THRESHOLD:
            self._explain(result, row, values)
            lap(Stage.STAGE_SHAP, mark)

        for stage, ms in timings:
            result.stage_timings.add(stage=stage, milliseconds=ms)
        result.scoring_duration_ms = math.ceil((time.perf_counter() - started) * 1000.0)
        return Scored(result=result, values=values, transaction=tx)

    def _explain(
        self,
        result: pb.ScoringResult,
        row: list[float],
        values: Mapping[str, features.FeatureValue],
    ) -> None:
        explanation = self.bundle.explain(row)
        # All 44: a registered feature the model was not given contributes exactly zero.
        contributions = {name: explanation.contributions.get(name, 0.0) for name in values}
        ordered = sorted(contributions.items(), key=lambda item: (-abs(item[1]), item[0]))
        for name, shap in ordered:
            result.shap_all.add(
                feature=name,
                value=feature_value(values[name]),
                shap=shap,
                increases_risk=shap > 0,
                template_key=template_key(name, shap > 0),
            )
        for contribution in result.shap_all[:TOP]:
            result.shap_top5.add().CopyFrom(contribution)
        result.shap_base_value = explanation.base_value
        result.final_margin = explanation.final_margin


class ModelHolder:
    """The blue-green switch: the current production and shadow scorers, swapped atomically.

    A request reads `production` once and uses that object to the end, so a swap mid-request never
    mixes two models and the old one keeps scoring until its last request finishes (FR-02-10).
    """

    def __init__(self, production: Scorer | None = None, shadow: Scorer | None = None) -> None:
        self._lock = threading.Lock()
        self._production = production
        self._shadow = shadow
        self.swaps = 0

    @property
    def production(self) -> Scorer | None:
        return self._production

    @property
    def shadow(self) -> Scorer | None:
        return self._shadow

    def swap_production(self, scorer: Scorer) -> Scorer | None:
        with self._lock:
            previous, self._production = self._production, scorer
            self.swaps += 1
            return previous

    def swap_shadow(self, scorer: Scorer | None) -> Scorer | None:
        with self._lock:
            previous, self._shadow = self._shadow, scorer
            return previous
