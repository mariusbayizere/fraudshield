"""The served model bundle: D-05's calibrated ensemble, D-06's forest, and what they were fed.

A bundle is a directory:

    manifest.json     versions, feature order, categorical encodings, weights, file hashes
    xgboost.json      XGBoost's own JSON model format
    lightgbm.txt      LightGBM's own text model format
    calibration.json  three isotonic calibrators: the ensemble's, and one per model (D-05)
    forest.json       the exported Isolation Forest and its reference distribution (D-06)

Nothing is pickled, so loading a bundle downloaded from the registry cannot run code; every file's
SHA-256 is in the manifest and checked on load, so a truncated or substituted file is refused
rather than served.

**D-05, precisely.** Each booster produces a raw probability. The ensemble's raw score is
`0.55·p_xgb + 0.45·p_lgb`, and **one** isotonic regression, fitted on the chronologically later
calibration split, maps it to `ensemble_score`. `xgboost_score` and `lightgbm_score` are each
model's own isotonic-calibrated probability, for display. SHAP is exact TreeSHAP per model in
log-odds margin space, combined as `0.55·φ_xgb + 0.45·φ_lgb` with the combined base value and final
margin reported beside it. The combined contributions sum to the combined margin, never to the
calibrated probability, and nothing here claims otherwise.

**TreeSHAP implementation.** E.4 names `shap.TreeExplainer`. Both boosters ship the same exact
TreeSHAP algorithm natively (`pred_contribs` / `pred_contrib`), with no sampling, so the `shap`
package and its dependencies are not added; the additivity test in margin space is the check that
the values are exact.
"""

from __future__ import annotations

import functools
import hashlib
import json
import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from fraudshield_ml.features.registry import REGISTRY
from fraudshield_ml.models.forest import Forest
from fraudshield_ml.models.isotonic import Isotonic

FORMAT_VERSION = 1
XGBOOST_WEIGHT = 0.55
LIGHTGBM_WEIGHT = 0.45
#: E.4 / FR-02-04: exact TreeSHAP is computed only at or above this ensemble score.
SHAP_THRESHOLD = 0.60

MANIFEST = "manifest.json"
FILES = ("xgboost.json", "lightgbm.txt", "calibration.json", "forest.json")

#: A feature value as the scorer assembles it: a number, a category, or NaN for missing (D-04).
FeatureValue = float | str


class BundleError(Exception):
    """A bundle that cannot be served: missing, corrupt, or built for another feature registry."""


def feature_registry_version() -> str:
    """A content hash of what the registry declares that changes what a model is fed.

    Semantic fields only — name, group, dtype, window, source, computability — so a clarified
    definition or leakage note does not orphan every trained model, while a changed window does.
    """
    digest = hashlib.sha256()
    for name in sorted(REGISTRY):
        spec = REGISTRY[name]
        digest.update(
            repr(
                (
                    spec.name,
                    spec.group.value,
                    spec.dtype.value,
                    spec.window,
                    spec.source.value,
                    spec.computable.value,
                )
            ).encode()
        )
    return "fr-" + digest.hexdigest()[:12]


@dataclass(frozen=True)
class Encoding:
    """The full-fit target encoding of one categorical: what `encode_categoricals` gives a
    held-out row, frozen for serving. An unseen category gets the prior, as it would there."""

    table: Mapping[str, float]
    prior: float

    def __call__(self, category: str) -> float:
        return self.table.get(category, self.prior)


@dataclass(frozen=True)
class Prediction:
    xgboost_raw: float
    lightgbm_raw: float
    ensemble_raw: float
    xgboost_score: float
    lightgbm_score: float
    ensemble_score: float


@dataclass(frozen=True)
class Explanation:
    """Weighted margin-space contributions, one per model input, with base and final margin."""

    contributions: Mapping[str, float]
    base_value: float
    final_margin: float
    #: Per-model additivity residuals |Σφ + bias - margin|, which FR-02-04 bounds at 0.001.
    additivity_error: float


@dataclass
class Bundle:
    model_version: str
    registry_version: str
    features: tuple[str, ...]
    encodings: Mapping[str, Encoding]
    xgboost: Any
    lightgbm: Any
    calibration: Mapping[str, Isotonic]
    forest: Forest
    #: Provenance and held-out metrics, carried verbatim from the build.
    provenance: Mapping[str, Any] = field(default_factory=dict)

    # ---------------------------------------------------------------- inference

    def row(self, values: Mapping[str, FeatureValue]) -> list[float]:
        """The model input for one transaction, in the bundle's feature order."""
        out: list[float] = []
        for name in self.features:
            value = values[name]
            if name in self.encodings:
                out.append(self.encodings[name](str(value)))
            else:
                out.append(float(value))
        return out

    def predict(self, row: Sequence[float]) -> Prediction:
        return self.calibrate(*self.raw(row))

    def raw(self, row: Sequence[float]) -> tuple[float, float]:
        """Each booster's uncalibrated probability for one row, through Treelite (ADR 0030).

        Treelite's tree inference is exact against both boosters to 1e-6 (tested per bundle in
        `test_treelite_matches_the_native_boosters`) and skips their Python wrappers, which cost
        more per call than the trees themselves: XGBoost's probes for pandas on every call.
        """
        import treelite  # noqa: PLC0415 - loaded with the bundle

        array = np.asarray([row], dtype=np.float64)
        compiled_xgb, compiled_lgb = self._compiled
        p_xgb = float(np.ravel(treelite.gtil.predict(compiled_xgb, array, nthread=1))[0])
        p_lgb = float(np.ravel(treelite.gtil.predict(compiled_lgb, array, nthread=1))[0])
        return p_xgb, p_lgb

    def native_raw(self, row: Sequence[float]) -> tuple[float, float]:
        """The boosters' own predictions: the reference Treelite is held to."""
        array = np.asarray([row], dtype=np.float64)
        p_xgb = float(self.xgboost.inplace_predict(array, missing=math.nan)[0])
        p_lgb = float(self.lightgbm.predict(array, num_threads=1)[0])
        return p_xgb, p_lgb

    @functools.cached_property
    def _compiled(self) -> tuple[Any, Any]:
        """Treelite models built from the same boosters, once per bundle."""
        import treelite  # noqa: PLC0415

        return (
            treelite.frontend.from_xgboost_json(self.xgboost.save_raw("json").decode()),
            treelite.frontend.from_lightgbm(self.lightgbm),
        )

    def calibrate(self, p_xgb: float, p_lgb: float) -> Prediction:
        """D-05: one isotonic map on the weighted combination, and one per model for display."""
        raw = XGBOOST_WEIGHT * p_xgb + LIGHTGBM_WEIGHT * p_lgb
        return Prediction(
            xgboost_raw=p_xgb,
            lightgbm_raw=p_lgb,
            ensemble_raw=raw,
            xgboost_score=self.calibration["xgboost"](p_xgb),
            lightgbm_score=self.calibration["lightgbm"](p_lgb),
            ensemble_score=self.calibration["ensemble"](raw),
        )

    def explain(self, row: Sequence[float]) -> Explanation:
        import xgboost as xgb  # noqa: PLC0415 - already loaded by the booster

        array = np.asarray([row], dtype=np.float64)
        # nthread=1 as D-16 requires: the default spins up an OpenMP team for a single row, which
        # measured ten times the cost of the trees on a busy machine.
        matrix = xgb.DMatrix(array, missing=math.nan, nthread=1)
        phi_xgb = self.xgboost.predict(matrix, pred_contribs=True)[0]
        margin_xgb = float(self.xgboost.predict(matrix, output_margin=True)[0])
        phi_lgb = self.lightgbm.predict(array, pred_contrib=True, num_threads=1)[0]
        margin_lgb = float(self.lightgbm.predict(array, raw_score=True, num_threads=1)[0])
        error = max(
            abs(float(np.sum(phi_xgb)) - margin_xgb), abs(float(np.sum(phi_lgb)) - margin_lgb)
        )
        weighted = XGBOOST_WEIGHT * phi_xgb + LIGHTGBM_WEIGHT * phi_lgb
        contributions = {name: float(weighted[i]) for i, name in enumerate(self.features)}
        # The margin the models actually produced, not the sum of the contributions: XGBoost
        # accumulates contributions in float32, so their sum differs from its margin by ~1e-6.
        # FR-02-04's tolerance (0.001) bounds that gap; reporting the true margin keeps the
        # waterfall honest about where it ends.
        return Explanation(
            contributions=contributions,
            base_value=float(weighted[-1]),
            final_margin=XGBOOST_WEIGHT * margin_xgb + LIGHTGBM_WEIGHT * margin_lgb,
            additivity_error=error,
        )

    def anomaly(self, row: Sequence[float]) -> tuple[float, float]:
        """(anomaly_score, anomaly_raw) per D-06."""
        raw = self.forest.raw(row)
        return self.forest.percentile(raw), raw

    # ---------------------------------------------------------------- persistence

    def save(self, directory: Path) -> Path:
        directory.mkdir(parents=True, exist_ok=True)
        self.xgboost.save_model(str(directory / "xgboost.json"))
        self.lightgbm.save_model(str(directory / "lightgbm.txt"))
        _write_json(
            directory / "calibration.json",
            {name: cal.to_json() for name, cal in sorted(self.calibration.items())},
        )
        _write_json(directory / "forest.json", self.forest.to_json())
        manifest = {
            "format_version": FORMAT_VERSION,
            "model_version": self.model_version,
            "feature_registry_version": self.registry_version,
            "features": list(self.features),
            "encodings": {
                name: {"table": dict(sorted(enc.table.items())), "prior": enc.prior}
                for name, enc in sorted(self.encodings.items())
            },
            "weights": {"xgboost": XGBOOST_WEIGHT, "lightgbm": LIGHTGBM_WEIGHT},
            "shap_threshold": SHAP_THRESHOLD,
            "provenance": dict(self.provenance),
            "files": {name: _sha256(directory / name) for name in FILES},
        }
        _write_json(directory / MANIFEST, manifest)
        return directory

    @staticmethod
    def load(directory: Path, *, expected_registry: str | None = None) -> Bundle:
        """Load and verify a bundle. `expected_registry` defaults to this code's registry."""
        import lightgbm as lgb  # noqa: PLC0415 - heavy
        import xgboost as xgb  # noqa: PLC0415 - heavy

        path = directory / MANIFEST
        if not path.exists():
            raise BundleError(f"{directory} has no {MANIFEST}")
        manifest = json.loads(path.read_text())
        if manifest.get("format_version") != FORMAT_VERSION:
            raise BundleError(
                f"{directory}: bundle format {manifest.get('format_version')!r}, this scorer "
                f"reads {FORMAT_VERSION}"
            )
        for name in FILES:
            declared = manifest["files"].get(name)
            if not (directory / name).exists() or _sha256(directory / name) != declared:
                raise BundleError(f"{directory}/{name} is missing or does not match its hash")
        wanted = expected_registry or feature_registry_version()
        if manifest["feature_registry_version"] != wanted:
            raise BundleError(
                f"{directory} was built for feature registry "
                f"{manifest['feature_registry_version']}, this scorer computes {wanted}; serving "
                "it would feed the model features it was not trained on"
            )
        unknown = set(manifest["features"]) - set(REGISTRY)
        if unknown:
            raise BundleError(f"{directory} names features the registry lacks: {sorted(unknown)}")

        booster = xgb.Booster()
        booster.load_model(str(directory / "xgboost.json"))
        booster.set_param({"nthread": 1})
        calibration = json.loads((directory / "calibration.json").read_text())
        return Bundle(
            model_version=str(manifest["model_version"]),
            registry_version=str(manifest["feature_registry_version"]),
            features=tuple(manifest["features"]),
            encodings={
                name: Encoding(table=dict(enc["table"]), prior=float(enc["prior"]))
                for name, enc in manifest["encodings"].items()
            },
            xgboost=booster,
            lightgbm=lgb.Booster(model_file=str(directory / "lightgbm.txt")),
            calibration={k: Isotonic.from_json(v) for k, v in calibration.items()},
            forest=Forest.from_json(json.loads((directory / "forest.json").read_text())),
            provenance=dict(manifest.get("provenance", {})),
        )


def _write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=1, sort_keys=False) + "\n")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
