"""The bundle serves D-05's ensemble exactly as it was built, and refuses to serve anything else."""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

import numpy as np
import pytest
import xgboost as xgb

from fraudshield_ml.features.registry import REGISTRY, Dtype, categories_for
from fraudshield_ml.models import build as builder
from fraudshield_ml.models.bundle import (
    LIGHTGBM_WEIGHT,
    XGBOOST_WEIGHT,
    Bundle,
    BundleError,
    feature_registry_version,
)
from fraudshield_ml.training import smoke

Vectors = list[dict[str, float | str]]
Built = tuple[Bundle, Path, Vectors, dict[str, list[str]]]


def synthetic_cache(rows: int, seed: int) -> tuple[Vectors, dict[str, list[str]]]:
    """Cache-shaped rows: fraud is driven by two features, so the models have something to learn."""
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    names = smoke.trainable_features()
    vectors: list[dict[str, float | str]] = []
    extras: dict[str, list[str]] = {k: [] for k in smoke.CACHE_EXTRAS}
    for i in range(rows):
        row: dict[str, float | str] = {}
        for name in names:
            if REGISTRY[name].dtype is Dtype.CATEGORICAL:
                row[name] = rng.choice(sorted(categories_for(name)))
            else:
                row[name] = math.nan if rng.random() < 0.05 else rng.gauss(0.0, 1.0)
        signal = (
            float(row[smoke.FLOOR_FEATURE])
            if not math.isnan(float(row[smoke.FLOOR_FEATURE]))
            else 0.0
        )
        fraud = rng.random() < 1 / (1 + math.exp(-(3.0 * signal - 4.0)))
        vectors.append(row)
        extras[smoke.CACHE_LABEL].append(str(fraud))
        extras[smoke.CACHE_ACCOUNT].append(f"acc{i % 400}")
        extras[smoke.CACHE_SEGMENT].append(
            "train" if i < rows * 0.5 else "calibration" if i < rows * 0.75 else "test"
        )
        extras[smoke.CACHE_COUNTRY].append("RW")
        extras[smoke.CACHE_CHANNEL].append("MOBILE_MONEY")
        extras[smoke.CACHE_VARIANT].append("")
    return vectors, extras


@pytest.fixture(scope="module")
def built(tmp_path_factory: pytest.TempPathFactory) -> Built:
    vectors, extras = synthetic_cache(3000, 11)
    bundle, report = builder.build(vectors, extras, seed=3, provenance={"source": "test"})
    assert report.ensemble_auc > 0.8, "the synthetic signal was learned"
    path = bundle.save(tmp_path_factory.mktemp("bundle"))
    return bundle, path, vectors, extras


@pytest.mark.req("FR-02-01", "FR-02-10")
def test_a_saved_bundle_loads_and_predicts_identically(built: Built) -> None:
    bundle, path, vectors, _ = built
    loaded = Bundle.load(path)
    assert loaded.model_version == bundle.model_version
    assert loaded.model_version.startswith("ensemble-")
    for values in vectors[:50]:
        row = bundle.row(values)
        assert loaded.row(values) == row
        assert loaded.predict(row) == bundle.predict(row)
        assert loaded.anomaly(row) == bundle.anomaly(row)


@pytest.mark.req("FR-02-01", "FR-02-03", "D-05")
def test_the_ensemble_is_one_calibration_of_the_weighted_raw_scores(built: Built) -> None:
    bundle, _, vectors, _ = built
    row = bundle.row(vectors[7])
    p = bundle.predict(row)
    assert p.ensemble_raw == pytest.approx(
        XGBOOST_WEIGHT * p.xgboost_raw + LIGHTGBM_WEIGHT * p.lightgbm_raw
    )
    assert p.ensemble_score == bundle.calibration["ensemble"](p.ensemble_raw)
    assert p.xgboost_score == bundle.calibration["xgboost"](p.xgboost_raw)
    assert p.lightgbm_score == bundle.calibration["lightgbm"](p.lightgbm_raw)
    for value in (p.ensemble_score, p.xgboost_score, p.lightgbm_score):
        assert 0.0 <= value <= 1.0


@pytest.mark.req("FR-02-04", "D-05")
def test_shap_is_additive_in_margin_space_per_model(built: Built) -> None:
    bundle, _, vectors, _ = built
    for values in vectors[:200]:
        explanation = bundle.explain(bundle.row(values))
        assert explanation.additivity_error < 0.001
        total = explanation.base_value + sum(explanation.contributions.values())
        assert total == pytest.approx(explanation.final_margin, abs=0.001)
        assert set(explanation.contributions) == set(bundle.features)


@pytest.mark.req("FR-02-04", "D-05")
def test_the_combined_margin_is_the_weighted_sum_of_the_model_margins(built: Built) -> None:
    bundle, _, vectors, _ = built
    row = bundle.row(vectors[3])
    array = np.asarray([row])
    margin_x = float(
        bundle.xgboost.predict(xgb.DMatrix(array, missing=math.nan), output_margin=True)[0]
    )
    margin_l = float(bundle.lightgbm.predict(array, raw_score=True)[0])
    expected = XGBOOST_WEIGHT * margin_x + LIGHTGBM_WEIGHT * margin_l
    assert bundle.explain(row).final_margin == pytest.approx(expected, abs=1e-12)


def test_the_frozen_encoding_is_what_held_out_rows_were_trained_against(built: Built) -> None:
    bundle, _, vectors, extras = built
    labels = [v == "True" for v in extras[smoke.CACHE_LABEL]]
    train = [i for i, s in enumerate(extras[smoke.CACHE_SEGMENT]) if s == "train"]
    encoded = smoke.encode_categoricals(vectors, labels, extras[smoke.CACHE_ACCOUNT], train)
    held_out = [i for i, s in enumerate(extras[smoke.CACHE_SEGMENT]) if s != "train"]
    for name, encoding in bundle.encodings.items():
        for i in held_out:
            assert encoding(str(vectors[i][name])) == pytest.approx(encoded[name][i], abs=1e-15)
    assert bundle.encodings["channel"]("NOT_A_CHANNEL") == bundle.encodings["channel"].prior


def test_a_tampered_file_is_refused(built: Built, tmp_path: Path) -> None:
    _, path, _, _ = built
    for name in (
        "manifest.json",
        "xgboost.json",
        "lightgbm.txt",
        "calibration.json",
        "forest.json",
    ):
        (tmp_path / name).write_bytes((path / name).read_bytes())
    calibration = json.loads((tmp_path / "calibration.json").read_text())
    calibration["ensemble"]["y"][-1] = 0.0
    (tmp_path / "calibration.json").write_text(json.dumps(calibration))
    with pytest.raises(BundleError, match="does not match its hash"):
        Bundle.load(tmp_path)


def test_a_bundle_for_another_feature_registry_is_refused(built: Built) -> None:
    _, path, _, _ = built
    with pytest.raises(BundleError, match="feature registry"):
        Bundle.load(path, expected_registry="fr-000000000000")


def test_an_empty_directory_and_a_wrong_format_are_refused(built: Built, tmp_path: Path) -> None:
    with pytest.raises(BundleError, match="no manifest"):
        Bundle.load(tmp_path)
    _, path, _, _ = built
    manifest = json.loads((path / "manifest.json").read_text())
    manifest["format_version"] = 99
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(BundleError, match="bundle format"):
        Bundle.load(tmp_path)


def test_unknown_features_are_refused(built: Built, tmp_path: Path) -> None:
    _, path, _, _ = built
    for name in ("xgboost.json", "lightgbm.txt", "calibration.json", "forest.json"):
        (tmp_path / name).write_bytes((path / name).read_bytes())
    manifest = json.loads((path / "manifest.json").read_text())
    manifest["features"] = [*manifest["features"], "not_a_feature"]
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(BundleError, match="registry lacks"):
        Bundle.load(tmp_path)


def test_the_registry_version_is_stable_and_shaped() -> None:
    assert feature_registry_version() == feature_registry_version()
    assert feature_registry_version().startswith("fr-")
    assert len(feature_registry_version()) == 15


def test_a_build_without_every_split_is_refused() -> None:
    vectors, extras = synthetic_cache(200, 1)
    extras[smoke.CACHE_SEGMENT] = ["train"] * 200
    with pytest.raises(ValueError, match="no calibration rows"):
        builder.build(vectors, extras, seed=1)


def test_the_report_states_the_floor_beside_the_model() -> None:
    report = builder.Report(
        rows={"train": 1, "calibration": 2, "test": 3},
        ensemble_auc=0.97,
        xgboost_auc=0.96,
        lightgbm_auc=0.95,
        floor=0.88,
        ece_ensemble=0.001,
        ece_raw=0.01,
        recall_at_1pct_fpr=0.9,
    )
    text = "\n".join(report.lines())
    assert "floor" in text
    assert "+0.0900" in text


def test_the_model_is_trained_on_the_whole_days_the_contract_carries() -> None:
    row = builder.serving_view(
        {"device_age_days": 2.9, "days_since_sim_swap": -0.5, "account_age_days": float("nan")}
    )
    assert row["device_age_days"] == 2.0
    assert math.isnan(float(row["days_since_sim_swap"])), "a negative age has no encoding"
    assert math.isnan(float(row["account_age_days"]))
