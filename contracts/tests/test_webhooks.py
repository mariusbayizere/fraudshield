from __future__ import annotations

import hmac
import json
from typing import Any

import pytest

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.events import topics, validate_event
from fraudshield_contracts.openapi import errors, load
from fraudshield_contracts.webhooks import Verdict, should_apply, sign, verify

WEBHOOKS = CONTRACTS_ROOT / "webhooks"


def _vectors(name: str) -> Any:
    raw = (WEBHOOKS / name).read_bytes()
    assert raw.isascii(), f"{name} must use JSON escapes"
    return json.loads(raw)


SIGNATURES = _vectors("signature-test-vectors.json")
ORDERING = _vectors("delivery-ordering-vectors.json")


@pytest.mark.req("D-14")
@pytest.mark.parametrize("vector", SIGNATURES["vectors"], ids=lambda v: v["name"])
def test_signature_vectors(vector: dict[str, Any]) -> None:
    verdict = verify(
        vector["header"], vector["body"].encode(), vector["secrets"], vector["verify_at"]
    )
    assert verdict == Verdict(vector["expected"])


def test_signature_vectors_cover_the_boundaries_and_rotation() -> None:
    names = " ".join(v["name"] for v in SIGNATURES["vectors"])
    for required in (
        "300 seconds old",
        "301 seconds old",
        "in the future",
        "rotation",
        "duplicate t",
    ):
        assert required in names
    assert {v["expected"] for v in SIGNATURES["vectors"]} == {e.value for e in Verdict}


def test_signatures_are_compared_in_constant_time(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, str]] = []
    original = hmac.compare_digest

    def recording_compare_digest(a: str, b: str) -> bool:
        calls.append((a, b))
        return original(a, b)

    monkeypatch.setattr(hmac, "compare_digest", recording_compare_digest)
    vector = SIGNATURES["vectors"][0]
    assert verify(vector["header"], vector["body"].encode(), vector["secrets"], vector["verify_at"])
    assert calls, "verify must use hmac.compare_digest"


def test_retries_are_signed_with_the_attempt_time() -> None:
    vector = SIGNATURES["vectors"][0]
    body = vector["body"].encode()
    [secret] = vector["secrets"]
    first_attempt = 1_789_624_961
    retry_attempt = first_attempt + 3_600
    retry_header = f"t={retry_attempt},v1={sign(secret, retry_attempt, body)}"
    assert verify(retry_header, body, [secret], retry_attempt + 5) is Verdict.VALID
    stale_header = f"t={first_attempt},v1={sign(secret, first_attempt, body)}"
    assert verify(stale_header, body, [secret], retry_attempt + 5) is Verdict.EXPIRED


@pytest.mark.req("D-14")
@pytest.mark.parametrize("scenario", ORDERING["scenarios"], ids=lambda s: s["name"])
def test_delivery_ordering_vectors(scenario: dict[str, Any]) -> None:
    last_applied: int | None = None
    for delivery in scenario["deliveries"]:
        apply = should_apply(last_applied, delivery["decision_sequence"])
        assert ("APPLY" if apply else "IGNORE") == delivery["expected"], delivery
        if apply:
            last_applied = delivery["decision_sequence"]


@pytest.mark.req("D-14")
def test_vector_body_is_a_valid_decision_state_on_kafka_and_in_the_api() -> None:
    topic = next(t for t in topics() if t.name == "fs.decisions.final")
    body = json.loads(SIGNATURES["vectors"][0]["body"])
    event = {
        "event_id": "0f5c1e38-2d4b-4a6f-9e10-7b8c9d0e1f2a",
        "event_type": "decision.final",
        "schema_version": 1,
        "occurred_at": "2026-09-17T06:02:41Z",
        "institution_id": "5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
        "producer": "fraudshield-api",
        "payload": body,
    }
    assert validate_event(topic, event) == []
    assert errors(load(), "FinalDecision", body) == []
