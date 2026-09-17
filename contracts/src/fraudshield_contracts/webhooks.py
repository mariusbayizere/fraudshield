"""Reference verifier for `decision.final` webhook signatures.

Specification: contracts/webhooks/decision-final.md.

The Java dispatcher and integrators' verifiers are tested against the same vectors.
"""

from __future__ import annotations

import hashlib
import hmac
import re
from enum import StrEnum

REPLAY_WINDOW_SECONDS = 300
_TIMESTAMP = re.compile(r"[0-9]{1,12}")
_SIGNATURE = re.compile(r"[0-9a-f]{64}")


class Verdict(StrEnum):
    VALID = "VALID"
    INVALID_SIGNATURE = "INVALID_SIGNATURE"
    EXPIRED = "EXPIRED"
    MALFORMED = "MALFORMED"


def sign(secret: str, timestamp: int, body: bytes) -> str:
    signed_payload = str(timestamp).encode() + b"." + body
    return hmac.new(secret.encode(), signed_payload, hashlib.sha256).hexdigest()


def verify(header: str, body: bytes, secrets: list[str], now: int) -> Verdict:
    """Verify an `X-FraudShield-Signature` header against one or more active secrets."""
    timestamps: list[int] = []
    signatures: list[str] = []
    for part in header.split(","):
        key, _, value = part.strip().partition("=")
        if key == "t":
            # ASCII digits only: str.isdigit() would also accept other scripts' digits.
            if not _TIMESTAMP.fullmatch(value):
                return Verdict.MALFORMED
            timestamps.append(int(value))
        elif key == "v1":
            if not _SIGNATURE.fullmatch(value):
                return Verdict.MALFORMED
            signatures.append(value)
    if len(timestamps) != 1 or not signatures:
        return Verdict.MALFORMED
    timestamp = timestamps[0]
    if abs(now - timestamp) > REPLAY_WINDOW_SECONDS:
        return Verdict.EXPIRED
    expected = [sign(secret, timestamp, body) for secret in secrets]
    if any(hmac.compare_digest(sig, exp) for sig in signatures for exp in expected):
        return Verdict.VALID
    return Verdict.INVALID_SIGNATURE


def should_apply(last_applied_sequence: int | None, decision_sequence: int) -> bool:
    """The receiver rule for decision states (ADR 0011 section 9).

    Apply a state only if its sequence is greater than the last one applied for the transaction;
    otherwise acknowledge the delivery and ignore it. Sequences, not decision values, give the
    order: the same decision can legitimately recur, and retries can arrive after newer states.
    """
    return last_applied_sequence is None or decision_sequence > last_applied_sequence
