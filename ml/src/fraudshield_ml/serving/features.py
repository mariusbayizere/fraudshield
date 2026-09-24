"""The 44 features from what the scoring contract carries: the transaction and its AccountContext.

This is the serving feature path, and the model was trained on the **batch** path's values, so the
only acceptable standard is agreement with batch. `tests/featurestore/test_parity.py` replays a
multi-account corpus through the Redis store and this module and compares every feature, at every
step, with `features.vector.compute` on the same prefix — within ADR 0025's tolerance for reals and
exactly for counts, flags and categories.

It computes from aggregates the store assembled, so it repeats the batch path's *arithmetic* (the
same smoothing, the same z-score scale, the same NaN rules) rather than its window filtering. The
constants are read from the registry, never restated.

**The one place the contract forces a difference.** `AccountContext` carries account age, device
age, counterparty age and days since SIM swap as whole days (`uint32`), where the batch path
computes fractional days. `models.build` therefore trains on the same whole-day view
(`WHOLE_DAY_FEATURES`), so the model never sees a value serving cannot reproduce; the parity test
compares these four at whole-day resolution and says so.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from fraudshield_ml.features.primitives import haversine_km
from fraudshield_ml.features.registry import REGISTRY, categories_for, smoothing_for
from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.serving.generated import scoring_pb2 as pb

FeatureValue = float | str

#: The four features the contract carries in whole days (see the module docstring).
WHOLE_DAY_FEATURES = (
    "account_age_days",
    "counterparty_account_age_days",
    "device_age_days",
    "days_since_sim_swap",
)

CHANNELS = {
    pb.CHANNEL_MOBILE_MONEY: "MOBILE_MONEY",
    pb.CHANNEL_CARD: "CARD",
    pb.CHANNEL_AGENT_BANKING: "AGENT_BANKING",
    pb.CHANNEL_USSD: "USSD",
    pb.CHANNEL_ONLINE: "ONLINE",
    pb.CHANNEL_BANK_TRANSFER: "BANK_TRANSFER",
}

MAD_TO_SIGMA = 1.4826
LIMIT_BAND = 0.05
MAX_IMPLIED_SPEED_KMH = 1000.0
NIGHT_HOURS = range(0, 5)
MONTH_START_DAYS, MONTH_END_DAYS = 2, 3
NEW_ACCOUNT_DAYS = 30.0
RAMP_RECENT_DAYS, RAMP_MONTH_DAYS = 7.0, 30.0
BASE_CURRENCY = "RWF"
NAN = math.nan
DEVICE_FEATURES = (
    "device_is_new_for_account",
    "accounts_per_device_7d",
    "device_changes_24h",
    "device_age_days",
)
AGENT_FEATURES = (
    "agent_float_utilisation_ratio",
    "agent_cashout_count_1h",
    "agent_unique_customers_1h",
    "agent_distance_from_registered_km",
)
_DOMESTIC, _INTRA_BLOC, _CROSS_BLOC, _INTERCONTINENTAL = categories_for("corridor_class")


class RequestError(ValueError):
    """A request the features cannot be computed from; the server answers INVALID_ARGUMENT."""


def _minimum(name: str) -> int:
    contract = REGISTRY[name].contract
    minimum = contract.minimum_history if contract is not None else None
    if minimum is None:  # pragma: no cover - the registry declares both features' thresholds
        raise ValueError(f"{name} declares no minimum_history")
    return minimum.minimum_observations


def _dormant_days() -> int:
    window = REGISTRY["dormancy_reactivation_flag"].window or ""
    return int(window.rstrip("d"))


def _decimal(value: str, field: str) -> Decimal:
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        raise RequestError(f"{field}={value!r} is not a decimal string") from None
    if not parsed.is_finite():
        raise RequestError(f"{field}={value!r} is not finite")
    return parsed


def domain_transaction(tx: pb.Transaction, reference: Reference) -> Transaction:
    """The contract's transaction as the feature paths' record, with the sender's country derived
    from its currency exactly as the training pipeline derived it (`cli.country_by_currency`)."""
    if not tx.transaction_id or not tx.account_token:
        raise RequestError("transaction_id and account_token are required")
    if tx.channel not in CHANNELS:
        raise RequestError(f"channel {tx.channel} is not one of the six declared channels")
    if not tx.HasField("transaction_timestamp"):
        raise RequestError("transaction_timestamp is required")
    currency = tx.amount.currency
    try:
        country = reference.account_country(currency)
    except ValueError as error:
        raise RequestError(str(error)) from None
    amount = _decimal(tx.amount.amount, "amount.amount")
    amount_rwf = _decimal(tx.amount_rwf, "amount_rwf")
    return Transaction(
        transaction_id=tx.transaction_id,
        account_id=tx.account_token,
        timestamp=tx.transaction_timestamp.ToDatetime(tzinfo=UTC),
        amount_rwf=float(amount_rwf),
        latitude=tx.location.latitude,
        longitude=tx.location.longitude,
        account_country=country,
        counterparty_country=(
            tx.counterparty_country if tx.HasField("counterparty_country") else None
        ),
        counterparty_id=tx.counterparty_token or None,
        # int(Decimal.scaleb(...)) truncates, as `cli.read_transactions` does for the dataset.
        amount_minor=int(amount.scaleb(reference.minor_units.get(currency, 0))),
        currency=currency,
        channel=CHANNELS[tx.channel],
        device_fingerprint=tx.device_token if tx.HasField("device_token") else None,
        agent_id=tx.agent_token if tx.HasField("agent_token") else None,
        merchant_category_code=tx.merchant_category_code or None,
    )


def compute(
    tx: Transaction,
    ctx: pb.AccountContext,
    limits: list[pb.Money],
    reference: Reference,
    exact_ages: Mapping[str, float] | None = None,
) -> dict[str, FeatureValue]:
    """All 44 features for one transaction, keyed by registered name.

    `exact_ages` carries the four `WHOLE_DAY_FEATURES` at full precision when the scorer read the
    store itself (ADR 0033); without it they come from the contract's whole-day fields.
    """
    if tx.counterparty_country is None:
        raise RequestError(
            "counterparty_country is required by is_new_country_for_account and corridor_class; "
            "there is no default"
        )
    if tx.counterparty_id is None:
        raise RequestError("counterparty_token is required by the counterparty features")
    previous_at = (
        ctx.last_transaction_at.ToDatetime(tzinfo=UTC)
        if ctx.HasField("last_transaction_at")
        else None
    )
    v: dict[str, FeatureValue] = {}
    v |= _velocity(ctx)
    v |= _amounts(tx, ctx, limits, reference)
    v |= _temporal(tx, reference, previous_at)
    v |= _geographic(tx, ctx, previous_at)
    v |= _counterparty(ctx)
    v |= _device(tx, ctx)
    v |= _profile(tx, ctx, previous_at)
    v |= _agent(tx, ctx)
    for name, value in (exact_ages or {}).items():
        if name in WHOLE_DAY_FEATURES and not (name == "device_age_days" and not _has_device(tx)):
            v[name] = value
    v["corridor_class"] = _corridor(tx, reference)
    v["synthetic_identity_score"] = _synthetic_identity(v, ctx, reference)
    missing = set(REGISTRY) - set(v)
    if missing:  # pragma: no cover - a structural guard, pinned by the completeness test
        raise AssertionError(f"serving vector is missing {sorted(missing)}")
    return v


def _optional(message: Any, name: str) -> float:
    """A proto3 optional field as a float, NaN when absent (D-04's missing value)."""
    return float(getattr(message, name)) if message.HasField(name) else NAN


def _velocity(ctx: pb.AccountContext) -> dict[str, FeatureValue]:
    alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha
    mean = ctx.mean_hourly_count_30d
    return {
        "velocity_ratio_1h_vs_30d": (
            NAN if math.isnan(mean) else (ctx.tx_count_1h + alpha) / (mean + alpha)
        ),
        "tx_count_60s": float(ctx.tx_count_60s),
        "tx_count_1h": float(ctx.tx_count_1h),
        "tx_count_24h": float(ctx.tx_count_24h),
        "tx_count_7d": float(ctx.tx_count_7d),
        "amount_sum_24h": float(ctx.amount_sum_24h_rwf or 0.0),
        "amount_sum_7d": float(ctx.amount_sum_7d_rwf or 0.0),
        "unique_counterparties_24h": float(ctx.unique_counterparties_24h),
    }


def _amounts(
    tx: Transaction, ctx: pb.AccountContext, limits: list[pb.Money], reference: Reference
) -> dict[str, FeatureValue]:
    zscore = NAN
    if ctx.history_count_90d >= _minimum("amount_zscore_90d") and ctx.HasField(
        "amount_mad_90d_rwf"
    ):
        mad = float(ctx.amount_mad_90d_rwf)
        if mad != 0.0:
            zscore = (tx.amount_rwf - float(ctx.amount_median_90d_rwf)) / (MAD_TO_SIGMA * mad)
    ratio = NAN
    if ctx.history_count_90d >= _minimum("amount_to_max_90d_ratio") and ctx.HasField(
        "amount_max_90d_rwf"
    ):
        largest = float(ctx.amount_max_90d_rwf)
        if largest > 0.0:
            ratio = tx.amount_rwf / largest
    steps = reference.denominations.get(tx.currency or "")
    return {
        "amount_log1p": math.log1p(tx.amount_rwf),
        "amount_zscore_90d": zscore,
        "amount_to_max_90d_ratio": ratio,
        "round_sum_flag": (
            float(any(s > 0 and (tx.amount_minor or 0) % s == 0 for s in steps)) if steps else NAN
        ),
        "just_below_limit_flag": float(_just_below(tx, limits)),
    }


def _temporal(
    tx: Transaction, reference: Reference, previous_at: datetime | None
) -> dict[str, FeatureValue]:
    offset = reference.countries[tx.account_country or ""].utc_offset_hours
    local = tx.timestamp + timedelta(hours=offset)
    return {
        "local_hour_sin": math.sin(2.0 * math.pi * local.hour / 24),
        "local_hour_cos": math.cos(2.0 * math.pi * local.hour / 24),
        "local_day_of_week": float(local.weekday()),
        "is_local_night": float(local.hour in NIGHT_HOURS),
        "is_month_end_window": float(
            local.day <= MONTH_START_DAYS or local.day > _days_in_month(local) - MONTH_END_DAYS
        ),
        "seconds_since_last_tx": (
            (tx.timestamp - previous_at).total_seconds() if previous_at else NAN
        ),
    }


def _geographic(
    tx: Transaction, ctx: pb.AccountContext, previous_at: datetime | None
) -> dict[str, FeatureValue]:
    distance = speed = NAN
    if previous_at is not None and ctx.HasField("last_location"):
        distance = haversine_km(
            ctx.last_location.latitude, ctx.last_location.longitude, tx.latitude, tx.longitude
        )
        hours = (tx.timestamp - previous_at).total_seconds() / 3600.0
        speed = min(distance / hours, MAX_IMPLIED_SPEED_KMH)
    centroid = NAN
    if ctx.HasField("home_centroid_90d"):
        home = ctx.home_centroid_90d
        centroid = haversine_km(home.latitude, home.longitude, tx.latitude, tx.longitude)
    return {
        "distance_from_last_tx_km": distance,
        "implied_speed_kmh": speed,
        "distance_from_home_centroid_km": centroid,
        "is_new_country_for_account": float(tx.counterparty_country not in ctx.countries_seen),
        "geo_cell_fraud_rate_30d": _optional(ctx, "geo_cell_fraud_rate_30d"),
    }


def _counterparty(ctx: pb.AccountContext) -> dict[str, FeatureValue]:
    return {
        "counterparty_is_new_for_account": float(ctx.counterparty_new_for_account),
        "counterparty_account_age_days": _optional(ctx, "counterparty_account_age_days"),
        "counterparty_unique_senders_24h": float(ctx.counterparty_unique_senders_24h),
        "counterparty_confirmed_fraud_90d": float(ctx.counterparty_confirmed_fraud_90d),
        "tx_count_to_counterparty_30d": float(ctx.tx_count_to_counterparty_30d),
    }


def _device(tx: Transaction, ctx: pb.AccountContext) -> dict[str, FeatureValue]:
    """Channel, and the four device features, which are NaN together without a fingerprint."""
    values: dict[str, FeatureValue] = {"channel": tx.channel or ""}
    if tx.device_fingerprint is None or not ctx.HasField("device"):
        return values | dict.fromkeys(DEVICE_FEATURES, NAN)
    d = ctx.device
    return values | {
        "device_is_new_for_account": float(d.device_new_for_account),
        "accounts_per_device_7d": float(d.accounts_per_device_7d),
        "device_changes_24h": float(d.device_changes_24h),
        "device_age_days": _optional(d, "device_age_days"),
    }


def _profile(
    tx: Transaction, ctx: pb.AccountContext, previous_at: datetime | None
) -> dict[str, FeatureValue]:
    dormant = previous_at is not None and previous_at <= tx.timestamp - timedelta(
        days=_dormant_days()
    )
    return {
        "account_age_days": _optional(ctx, "account_age_days"),
        "kyc_tier": _optional(ctx, "kyc_tier"),
        "days_since_sim_swap": _optional(ctx, "days_since_sim_swap"),
        "dormancy_reactivation_flag": float(dormant),
    }


def _agent(tx: Transaction, ctx: pb.AccountContext) -> dict[str, FeatureValue]:
    """The four agent features, NaN together outside an agent transaction."""
    if tx.agent_id is None or not ctx.HasField("agent"):
        return dict.fromkeys(AGENT_FEATURES, NAN)
    g = ctx.agent
    return {
        "agent_float_utilisation_ratio": _optional(g, "float_utilisation_ratio"),
        "agent_cashout_count_1h": float(g.cashout_count_1h),
        "agent_unique_customers_1h": float(g.unique_customers_1h),
        "agent_distance_from_registered_km": _optional(g, "distance_from_registered_km"),
    }


def _just_below(tx: Transaction, limits: list[pb.Money]) -> bool:
    """Limits arrive already resolved by the API (in force now, for this channel and tier), so
    the batch path's as-of selection has happened upstream; what remains is the band test, in the
    base currency the batch path compares in."""
    flagged = False
    for limit in limits:
        if limit.currency != BASE_CURRENCY:
            raise RequestError(
                f"configured limit in {limit.currency!r}; limits are compared in {BASE_CURRENCY} "
                "against amount_rwf, as the training features were"
            )
        value = float(_decimal(limit.amount, "configured_limits.amount"))
        flagged |= value * (1.0 - LIMIT_BAND) <= tx.amount_rwf < value
    return flagged


def _has_device(tx: Transaction) -> bool:
    return tx.device_fingerprint is not None


def _corridor(tx: Transaction, reference: Reference) -> str:
    sender = reference.countries[tx.account_country or ""]
    recipient = reference.countries.get(tx.counterparty_country or "")
    if recipient is None:
        raise RequestError(f"no country pack for counterparty_country {tx.counterparty_country!r}")
    if sender.alpha2 == recipient.alpha2:
        return _DOMESTIC
    if sender.blocs & recipient.blocs:
        return _INTRA_BLOC
    if sender.continent == recipient.continent:
        return _CROSS_BLOC
    return _INTERCONTINENTAL


def _synthetic_identity(
    v: dict[str, FeatureValue], ctx: pb.AccountContext, reference: Reference
) -> float:
    lowest, highest = reference.kyc_tier_range
    tier = float(v["kyc_tier"])
    kyc = 0.0 if math.isnan(tier) else min(1.0, max(0.0, (highest - tier) / (highest - lowest)))
    # The exact age when known; otherwise whole days, where floor(age) < 30 exactly when age < 30.
    age = float(v["account_age_days"])
    new = 1.0 if not math.isnan(age) and age < NEW_ACCOUNT_DAYS else 0.0
    on_device = float(v["accounts_per_device_7d"])
    sharing = 0.0 if math.isnan(on_device) else min(1.0, max(0.0, (on_device - 1.0) / 2.0))
    even = RAMP_RECENT_DAYS / RAMP_MONTH_DAYS
    ramp = 0.0
    if ctx.HasField("volume_ramp_ratio_7d"):
        ramp = min(1.0, max(0.0, (ctx.volume_ramp_ratio_7d - even) / (1.0 - even)))
    return (kyc + new + sharing + ramp) / 4


def _days_in_month(moment: datetime) -> int:
    if moment.month == 12:
        following = moment.replace(year=moment.year + 1, month=1, day=1)
    else:
        following = moment.replace(month=moment.month + 1, day=1)
    return (following - timedelta(days=1)).day
