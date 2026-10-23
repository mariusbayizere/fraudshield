from __future__ import annotations

import re

import numpy as np
import pytest

from fraudshield_dataset.generator.keys import SHARDS, shard_of, stream, token, transaction_uuid

pytestmark = pytest.mark.req("D-08")


def test_streams_depend_only_on_seed_and_keys() -> None:
    first = stream(7, "customer", 3, "2024-01").random(5)
    again = stream(7, "customer", 3, "2024-01").random(5)
    other_month = stream(7, "customer", 3, "2024-02").random(5)
    other_seed = stream(8, "customer", 3, "2024-01").random(5)
    np.testing.assert_array_equal(first, again)
    assert not np.array_equal(first, other_month)
    assert not np.array_equal(first, other_seed)


def test_tokens_match_the_contract_pattern_and_are_stable() -> None:
    values = {token(1, "account", i) for i in range(2000)}
    assert len(values) == 2000
    assert all(re.fullmatch(r"tok_[A-Za-z0-9]{24,64}", value) for value in values)
    assert token(1, "account", 5) == token(1, "account", 5)
    assert token(1, "account", 5) != token(2, "account", 5)


def test_transaction_ids_are_uuid4_shaped_and_unordered() -> None:
    ids = [transaction_uuid(1, "customer", 0, "2024-01", n) for n in range(1000)]
    assert len(set(ids)) == 1000
    assert all(
        re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", i)
        for i in ids
    )
    # Generation order must not survive in the identifier order.
    ranks = np.argsort(np.argsort(ids))
    correlation = np.corrcoef(ranks, np.arange(1000))[0, 1]
    assert abs(correlation) < 0.1


def test_shards_are_balanced() -> None:
    counts = np.bincount([shard_of(11, c) for c in range(64_000)], minlength=SHARDS)
    assert counts.min() > 800
    assert counts.max() < 1200
