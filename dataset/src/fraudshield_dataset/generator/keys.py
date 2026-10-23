"""Keyed random streams and identifiers (ADR 0022 section 2).

Every random draw comes from a generator seeded by ``(seed, stream name, entity keys...)``, never
from processing order, so simulating shards in any batch size yields the same values. Identifiers
are keyed BLAKE2b digests: they carry no order, time or label information.
"""

from __future__ import annotations

import hashlib
import uuid

import numpy as np

_BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
SHARDS = 64


def _digest(seed: int, parts: tuple[object, ...], size: int = 32) -> bytes:
    material = "\x1f".join(str(p) for p in parts).encode("utf-8")
    return hashlib.blake2b(material, digest_size=size, key=seed.to_bytes(8, "big")).digest()


def stream(seed: int, *parts: object) -> np.random.Generator:
    """An independent PCG64 generator for ``parts`` (for example ``"customer", 17, "2024-03"``)."""
    entropy = int.from_bytes(_digest(seed, parts), "big")
    return np.random.Generator(np.random.PCG64(np.random.SeedSequence(entropy)))


def token(seed: int, *parts: object) -> str:
    """An opaque 32-character ``tok_`` identifier (contract ``^tok_[A-Za-z0-9]{24,64}$``)."""
    number = int.from_bytes(_digest(seed, ("token", *parts), size=24), "big")
    characters = []
    for _ in range(32):
        number, remainder = divmod(number, 62)
        characters.append(_BASE62[remainder])
    return "tok_" + "".join(characters)


def transaction_uuid(seed: int, *parts: object) -> str:
    """A UUID with version-4 layout whose bits are a keyed digest of ``parts``."""
    return str(uuid.UUID(bytes=_digest(seed, ("transaction", *parts), size=16), version=4))


def shard_of(seed: int, customer: int) -> int:
    """Fixed shard of a customer; the shard count is part of the dataset definition."""
    return int.from_bytes(_digest(seed, ("shard", customer), size=8), "big") % SHARDS
