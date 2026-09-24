"""Shared configuration, credentials and payload factories for the ingest load tests.

**NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).** The
ingest API does not exist as code on any merged branch, and no machine recorded in
`docs/benchmarks/hardware.md` can carry the specified load. These modules are written against the
frozen contract (`contracts/openapi/fraudshield-api.yaml`, `info.version 1.0.0-m1`) so that the
campaign can run the moment the integrated system and the benchmark machine exist.

Every payload here is built from the contract's own schemas: tokens match `Token`, amounts match
`PositiveAmount` (decimal **strings**, never JSON numbers), `agent_id` is present exactly when the
channel is `AGENT_BANKING`, and timestamps are generated at send time in UTC because the server
rejects a timestamp more than five minutes in the future.
"""

# Load generation needs speed and reproducibility, not unpredictability: every draw below chooses a
# synthetic account, channel or amount, and none protects anything.

from __future__ import annotations

import os
import random
import string
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

#: Base path of every ingest operation (`servers[0].url` in the OpenAPI document).
API_ROOT = "/api/v1"

#: Channel mix as the specification calibrates it (`dataset/generator/params/channels.yaml`,
#: CALIBRATED_TO_SRS_TARGET, SRS 7.1 / ML-DATA-03). The load mix follows the data the models were
#: trained on, so the measured throughput describes the traffic the system is specified to carry.
CHANNEL_SHARE: dict[str, float] = {
    "MOBILE_MONEY": 0.41,
    "USSD": 0.18,
    "AGENT_BANKING": 0.14,
    "CARD": 0.12,
    "ONLINE": 0.09,
    "BANK_TRANSFER": 0.06,
}

#: Currencies of the simulated countries, from the ingest schema's enum.
CURRENCIES = ("RWF", "KES", "TZS", "UGX", "CDF")

#: Merchant category codes used by the contract's own examples.
MERCHANT_CATEGORY_CODES = ("4829", "5411", "5812", "6011", "4900")

#: Coordinates inside the five simulated countries, so geographic features are not all identical.
CITIES = (
    (-1.9441, 30.0619),
    (-1.2921, 36.8219),
    (-6.7924, 39.2083),
    (0.3476, 32.5825),
    (-4.4419, 15.2663),
)

TOKEN_ALPHABET = string.ascii_letters + string.digits


class CredentialsMissingError(RuntimeError):
    """Raised when no API key is available, rather than sending unauthenticated load."""


@dataclass(frozen=True)
class Settings:
    """Everything the run reads from the environment.

    The API key is never committed: `make seed-demo` writes one to the git-ignored
    `.demo-credentials` and exports it as ``FRAUDSHIELD_DEMOSEED_APIKEY``; an operator may instead
    mint one with `POST /api/v1/admin/api-keys` and pass it as ``FS_PERF_API_KEY``.
    """

    api_key: str
    #: Accounts each worker cycles through, so velocity features see repeated history per account.
    account_pool: int = 2_000
    #: Counterparties per account; a small pool makes most transfers go to a known payee.
    counterparties_per_account: int = 8
    #: Share of requests that resend an already-accepted transaction unchanged (FR-01-03).
    replay_share: float = 0.02
    #: Fail the run if the target reports that it is not serving synthetic data.
    require_synthetic: bool = True

    @classmethod
    def from_env(cls) -> Settings:
        key = os.environ.get("FS_PERF_API_KEY") or os.environ.get("FRAUDSHIELD_DEMOSEED_APIKEY")
        if not key:
            raise CredentialsMissingError(
                "set FS_PERF_API_KEY (or FRAUDSHIELD_DEMOSEED_APIKEY from `make seed-demo`); "
                "the ingest API authenticates with X-API-Key and this harness never embeds a key"
            )
        return cls(
            api_key=key,
            account_pool=int(os.environ.get("FS_PERF_ACCOUNT_POOL", "2000")),
            replay_share=float(os.environ.get("FS_PERF_REPLAY_SHARE", "0.02")),
            require_synthetic=os.environ.get("FS_PERF_ALLOW_REAL_DATA") != "1",
        )


def headers(settings: Settings) -> dict[str, str]:
    """Request headers for every ingest call: API key auth, JSON only (415 otherwise)."""
    return {
        "X-API-Key": settings.api_key,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }


def token(rng: random.Random) -> str:
    """A value matching the contract's `Token` pattern `^tok_[A-Za-z0-9]{24,64}$`."""
    return "tok_" + "".join(rng.choices(TOKEN_ALPHABET, k=24))


def amount(rng: random.Random) -> str:
    """A value matching `PositiveAmount`: a decimal string, never zero, at most four decimals."""
    units = rng.randrange(100, 2_000_000)
    return f"{units // 100}.{units % 100:02d}"


def timestamp_now() -> str:
    """RFC 3339 UTC with the trailing `Z` the schema's pattern requires.

    Generated per request rather than once per run: the server rejects a timestamp more than five
    minutes ahead of its own clock, and a sustained plateau outlives a fixed value.
    """
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def choose_channel(rng: random.Random) -> str:
    """A channel drawn from the specified mix."""
    return rng.choices(tuple(CHANNEL_SHARE), weights=tuple(CHANNEL_SHARE.values()), k=1)[0]


@dataclass
class AccountPool:
    """Stable accounts, counterparties and devices for one worker process.

    A load test that invents a new account for every request measures a system with no history:
    every velocity and counterparty feature would read its empty-history fallback, and the feature
    store would never hit its cache. The pool gives each account a small set of regular payees, so
    the traffic resembles the population the models were trained on.
    """

    settings: Settings
    rng: random.Random
    accounts: list[str] = field(default_factory=list)
    counterparties: dict[str, list[str]] = field(default_factory=dict)
    devices: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.accounts = [token(self.rng) for _ in range(self.settings.account_pool)]
        for account in self.accounts:
            self.counterparties[account] = [
                token(self.rng) for _ in range(self.settings.counterparties_per_account)
            ]
            self.devices[account] = token(self.rng)

    def pick(self) -> tuple[str, str, str]:
        """An account, one of its regular counterparties, and its device."""
        account = self.rng.choice(self.accounts)
        return account, self.rng.choice(self.counterparties[account]), self.devices[account]


def transaction(pool: AccountPool, *, channel: str | None = None) -> dict[str, Any]:
    """One `TransactionIngestRequest` body, valid against the frozen schema."""
    rng = pool.rng
    account, counterparty, device = pool.pick()
    chosen = channel or choose_channel(rng)
    latitude, longitude = rng.choice(CITIES)
    body: dict[str, Any] = {
        "transaction_id": str(uuid4()),
        "account_id": account,
        "counterparty_id": counterparty,
        "amount": amount(rng),
        "currency": rng.choice(CURRENCIES),
        "channel": chosen,
        "merchant_category_code": rng.choice(MERCHANT_CATEGORY_CODES),
        "latitude": round(latitude + rng.uniform(-0.05, 0.05), 6),
        "longitude": round(longitude + rng.uniform(-0.05, 0.05), 6),
        # Explicitly null on USSD: feature phones carry no fingerprint (D-04), and the schema
        # distinguishes a null from an absent field in its examples.
        "device_fingerprint": None if chosen == "USSD" else device,
        "transaction_timestamp": timestamp_now(),
    }
    if chosen == "AGENT_BANKING":
        # Required when and only when the channel is AGENT_BANKING (allOf/if-then in the schema).
        body["agent_id"] = token(rng)
    return body


#: Fields of `DecisionResponse` that the contract marks required (nullable ones are present with
#: a null value, not absent). A load test that only counts HTTP 200s cannot see a response that
#: has stopped carrying its decision, so every scenario checks this set.
DECISION_FIELDS = (
    "transaction_id",
    "decision",
    "risk_tier",
    "reason_codes",
    "scoring_result_id",
    "model_version",
    "decision_latency_ms",
    "review_deadline_at",
    "ml_unavailable_fallback",
)


def decision_problem(payload: dict[str, Any]) -> str | None:
    """The first contract violation in a decision body, or None.

    Checks the invariants the schema states, not merely the field list: a HOLD carries a review
    deadline and the MEDIUM tier, an APPROVE is LOW, and a HIGH tier declines.
    """
    missing = [name for name in DECISION_FIELDS if name not in payload]
    if missing:
        return f"missing fields: {', '.join(missing)}"
    decision, tier = payload["decision"], payload["risk_tier"]
    if decision == "HOLD" and (payload["review_deadline_at"] is None or tier != "MEDIUM"):
        return "HOLD without a MEDIUM tier and a review deadline"
    if decision == "APPROVE" and tier != "LOW":
        return f"APPROVE with risk tier {tier}"
    if tier == "HIGH" and decision != "DECLINE":
        return f"HIGH risk tier with decision {decision}"
    if len(payload["reason_codes"]) > 3:
        return "more than three reason codes"
    return None
