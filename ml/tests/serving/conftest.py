"""Fixtures for the serving tests: two small bundles and a request builder.

Two bundles with different seeds, so hot-swap tests can tell which model produced a result. They
are trained on synthetic rows in which `velocity_ratio_1h_vs_30d` drives fraud, so a request with a
burst in its `AccountContext` scores high and exercises the SHAP path.
"""

from __future__ import annotations

import random
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from fraudshield_ml.features.types import CountryFacts, Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.models import build
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import features
from fraudshield_ml.serving.convert import to_proto
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.training import smoke

REFERENCE = Reference(
    countries={
        "RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2),
        "KE": CountryFacts("KE", "AF", frozenset({"EAC"}), 3),
    },
    country_of_currency={"RWF": "RW", "KES": "KE"},
    minor_units={"RWF": 0, "KES": 2},
    denominations={"RWF": (500, 1000)},
)
T0 = datetime(2025, 7, 1, 10, tzinfo=UTC)


def _cache(rows: int, seed: int) -> tuple[list[dict[str, float | str]], dict[str, list[str]]]:
    """Training rows produced by the serving feature path from ordinary-looking requests.

    Generated through `serving.features.compute` rather than drawn per feature, so the forest learns
    what a normal request looks like and a genuine outlier stands out against it.
    """
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    names = smoke.trainable_features()
    vectors: list[dict[str, float | str]] = []
    extras: dict[str, list[str]] = {k: [] for k in smoke.CACHE_EXTRAS}
    for i in range(rows):
        burst = rng.random() < 0.08
        amount = round(rng.lognormvariate(9.3, 0.6))
        tx = transaction(
            i,
            amount_rwf=float(amount),
            amount_minor=amount,
            latitude=-1.95 + rng.gauss(0, 0.02),
            longitude=30.06 + rng.gauss(0, 0.02),
            channel=rng.choice(["MOBILE_MONEY", "CARD", "ONLINE", "BANK_TRANSFER"]),
        )
        ctx = context(burst=burst)
        if not burst:
            ctx.tx_count_1h = rng.randint(0, 2)
            ctx.mean_hourly_count_30d = rng.uniform(0.3, 1.5)
        ctx.amount_median_90d_rwf = repr(float(round(rng.lognormvariate(9.3, 0.3))))
        ctx.last_transaction_at.FromDatetime(tx.timestamp - timedelta(minutes=rng.randint(5, 900)))
        values = features.compute(tx, ctx, [], REFERENCE)
        vectors.append({name: values[name] for name in names})
        extras[smoke.CACHE_LABEL].append(str(burst and rng.random() < 0.9))
        extras[smoke.CACHE_ACCOUNT].append(f"a{i % 300}")
        extras[smoke.CACHE_SEGMENT].append(
            "train" if i < rows * 0.6 else "calibration" if i < rows * 0.85 else "test"
        )
        for key in (smoke.CACHE_COUNTRY, smoke.CACHE_CHANNEL, smoke.CACHE_VARIANT):
            extras[key].append("")
    return vectors, extras


def make_bundle(directory: Path, seed: int) -> Bundle:
    vectors, extras = _cache(2500, seed)
    bundle, _ = build.build(vectors, extras, seed=seed)
    bundle.save(directory)
    return Bundle.load(directory)


@pytest.fixture(scope="session")
def bundle_dirs(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path]:
    a, b = tmp_path_factory.mktemp("bundle_a"), tmp_path_factory.mktemp("bundle_b")
    make_bundle(a, 1)
    make_bundle(b, 2)
    return a, b


@pytest.fixture(scope="session")
def bundles(bundle_dirs: tuple[Path, Path]) -> tuple[Bundle, Bundle]:
    first, second = (Bundle.load(d) for d in bundle_dirs)
    assert first.model_version != second.model_version
    return first, second


def transaction(i: int = 0, **overrides: object) -> Transaction:
    fields: dict[str, object] = {
        "transaction_id": f"3f8e0c5a-6b1d-4f5e-9a2b-{i:012d}",
        "account_id": "tok_acc",
        "timestamp": T0 + timedelta(seconds=i),
        "amount_rwf": 12_000.0,
        "latitude": -1.95,
        "longitude": 30.06,
        "account_country": "RW",
        "counterparty_country": "RW",
        "counterparty_id": "tok_cp",
        "amount_minor": 12_000,
        "currency": "RWF",
        "channel": "MOBILE_MONEY",
        "device_fingerprint": "tok_dev",
        "merchant_category_code": "5411",
    }
    return Transaction(**(fields | overrides))  # type: ignore[arg-type]


def context(*, burst: bool = False, **overrides: object) -> pb.AccountContext:
    ctx = pb.AccountContext(
        tx_count_1h=40 if burst else 1,
        tx_count_24h=41 if burst else 3,
        tx_count_7d=45 if burst else 12,
        amount_sum_24h_rwf="30000.0",
        amount_sum_7d_rwf="90000.0",
        mean_hourly_count_30d=0.0 if burst else 0.8,
        history_count_90d=30,
        amount_median_90d_rwf="10000.0",
        amount_mad_90d_rwf="2000.0",
        amount_max_90d_rwf="25000.0",
        countries_seen=["RW"],
        geo_cell_fraud_rate_30d=0.01,
    )
    ctx.last_location.CopyFrom(pb.GeoPoint(latitude=-1.95, longitude=30.06))
    ctx.last_transaction_at.FromDatetime(T0 - timedelta(hours=2))
    ctx.home_centroid_90d.CopyFrom(pb.GeoPoint(latitude=-1.95, longitude=30.06))
    ctx.device.CopyFrom(pb.DeviceContext(accounts_per_device_7d=1))
    for name, value in overrides.items():
        if isinstance(value, list):
            del getattr(ctx, name)[:]
            getattr(ctx, name).extend(value)
        else:
            setattr(ctx, name, value)
    return ctx


def request(
    i: int = 0,
    *,
    burst: bool = False,
    tx: Transaction | None = None,
    ctx: pb.AccountContext | None = None,
) -> pb.ScoreRequest:
    return pb.ScoreRequest(
        transaction=to_proto(
            tx or transaction(i), institution="5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b"
        ),
        context=ctx or context(burst=burst),
        traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    )


@pytest.fixture(scope="session")
def kit() -> SimpleNamespace:
    """The builders, as a fixture: test modules are not a package, so they cannot import them."""
    return SimpleNamespace(
        transaction=transaction,
        context=context,
        request=request,
        t0=T0,
        reference=REFERENCE,
        cache=_cache,
    )


@pytest.fixture(scope="session")
def reference() -> Reference:
    return REFERENCE
