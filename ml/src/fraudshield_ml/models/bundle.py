"""The served model bundle: M4's evaluated D-05 ensemble and D-06 forest, packaged without pickle.

**The served model is the evaluated model.** M4's gate figures (ML-GATE-01 to 11) and the paper
describe `training.model.fit_ensemble` and `training.anomaly.fit_anomaly`. A bundle packages
exactly those fitted objects (`models.build`), and `models.build` refuses to write one whose scores
differ from the fitted model's by 1e-5 or more anywhere in the test period.

A bundle is a directory:

    manifest.json     versions, feature order, categorical encodings, round counts, file hashes
    xgboost.json      XGBoost, truncated to the rounds early stopping chose (its native format)
    lightgbm.txt      LightGBM, truncated and float32-exact (M4's construction; its native format)
    xgboost.onnx      M4's ONNX export of the same XGBoost trees: what `raw` scores with
    lightgbm.onnx     M4's ONNX export of the same LightGBM trees
    calibration.json  three isotonic calibrators as knots: the ensemble's, and one per model
    forest.json       the Isolation Forest's trees, imputation medians and reference distribution

Nothing is pickled, so loading a bundle downloaded from the registry cannot run code, and every
file's SHA-256 is in the manifest and checked on load.

**Inference is ONNX Runtime** (ADR 0032, amended), because M4's float32 construction makes it exact.
Every input is rounded to float32, as `Ensemble.raw` does, and every LightGBM threshold was moved to
the largest float32 not above it. Measured on the whole test period, ONNX matches the native
boosters to 9e-7, and it scores both in 0.07 ms per row against Treelite's 0.35 ms. The native
boosters stay for exact TreeSHAP and as the parity reference (`native_raw`).

**D-05, precisely.** `ensemble_score` is one isotonic regression applied to
`0.55·p_xgb + 0.45·p_lgb`; `xgboost_score` and `lightgbm_score` are each model's own calibrated
probability, for display. SHAP is exact TreeSHAP per model in margin space, combined as
`0.55·φ_xgb + 0.45·φ_lgb`. It sums to the combined margin, never to the calibrated probability.
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

FORMAT_VERSION = 2
XGBOOST_WEIGHT = 0.55
LIGHTGBM_WEIGHT = 0.45
#: E.4 / FR-02-04: exact TreeSHAP is computed only at or above this ensemble score.
SHAP_THRESHOLD = 0.60
#: E.4's parity bound between the served inference path and the native boosters.
PARITY = 1e-5

MANIFEST = "manifest.json"
FILES = (
    "xgboost.json",
    "lightgbm.txt",
    "xgboost.onnx",
    "lightgbm.onnx",
    "calibration.json",
    "forest.json",
)
ONNX_INPUT = "input"

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
    #: Per-model additivity residuals |sum(phi) + bias - margin|, which FR-02-04 bounds at 0.001.
    additivity_error: float


@dataclass
class Bundle:
    model_version: str
    registry_version: str
    features: tuple[str, ...]
    encodings: Mapping[str, Encoding]
    #: Native boosters, already truncated to the scored rounds: SHAP and the parity reference.
    xgboost: Any
    lightgbm: Any
    #: M4's ONNX exports of the same trees, serialised: what `raw` scores with.
    xgboost_onnx: bytes
    lightgbm_onnx: bytes
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
        """Each booster's uncalibrated probability for one row, through ONNX Runtime."""
        array = np.asarray([row], dtype=np.float32)
        session_xgb, session_lgb = self._sessions
        p_xgb = float(session_xgb.run(None, {ONNX_INPUT: array})[1][0, 1])
        p_lgb = float(session_lgb.run(None, {ONNX_INPUT: array})[1][0, 1])
        return p_xgb, p_lgb

    def native_raw(self, rows: Sequence[Sequence[float]]) -> tuple[Any, Any]:
        """The boosters' own probabilities on float32 inputs, as `Ensemble.raw` computes them."""
        array = np.asarray(rows, dtype=np.float32)
        p_xgb = self.xgboost.inplace_predict(array, missing=math.nan)
        p_lgb = self.lightgbm.predict(array, num_threads=1)
        return np.asarray(p_xgb, dtype=np.float64), np.asarray(p_lgb, dtype=np.float64)

    def onnx_raw(self, rows: Sequence[Sequence[float]]) -> tuple[Any, Any]:
        """The ONNX path over many rows at once, for the parity check."""
        array = np.asarray(rows, dtype=np.float32)
        session_xgb, session_lgb = self._sessions
        return (
            np.asarray(session_xgb.run(None, {ONNX_INPUT: array})[1][:, 1], dtype=np.float64),
            np.asarray(session_lgb.run(None, {ONNX_INPUT: array})[1][:, 1], dtype=np.float64),
        )

    @functools.cached_property
    def _sessions(self) -> tuple[Any, Any]:
        import onnxruntime as ort  # noqa: PLC0415 - loaded with the bundle

        options = ort.SessionOptions()
        # D-16: one thread per inference; throughput comes from worker processes.
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        return tuple(
            ort.InferenceSession(model, options, providers=["CPUExecutionProvider"])
            for model in (self.xgboost_onnx, self.lightgbm_onnx)
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

        # float32, as the boosters score: an explanation of a different routing is not one.
        array = np.asarray([row], dtype=np.float32)
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
        (directory / "xgboost.onnx").write_bytes(self.xgboost_onnx)
        (directory / "lightgbm.onnx").write_bytes(self.lightgbm_onnx)
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
            "inference": "onnxruntime",
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
            xgboost_onnx=(directory / "xgboost.onnx").read_bytes(),
            lightgbm_onnx=(directory / "lightgbm.onnx").read_bytes(),
            calibration={k: Isotonic.from_json(v) for k, v in calibration.items()},
            forest=Forest.from_json(json.loads((directory / "forest.json").read_text())),
            provenance=dict(manifest.get("provenance", {})),
        )


def _write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=1, sort_keys=False) + "\n")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
