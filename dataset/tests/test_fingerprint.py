"""The dataset fingerprint (PB-41), including the mutation that made it necessary.

The parameter digest answers "did the inputs change?". PB-29 changed no parameter value and
re-drew the entire benchmark, because `countries.simulated()` sorts and country iteration went
from declaration order to alphabetical. These tests reproduce that exact shape — a changed
iteration order with byte-identical parameters — and assert that the parameter digest is blind to
it while the fingerprint is not. Without the first half of that assertion the second proves only
that two different datasets hash differently, which was never in doubt.
"""

from __future__ import annotations

import math
import shutil
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from fraudshield_dataset.fingerprint import (
    SAMPLE_ROWS,
    FingerprintError,
    dataset_fingerprint,
    table_fingerprint,
)
from fraudshield_dataset.generator import countries, population
from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import ParameterSet, load_parameters
from fraudshield_dataset.paths import REALISM_REPORT_MD
from fraudshield_dataset.realism.checks import parameter_digest

pytestmark = pytest.mark.req("ML-DATA-08")

SEED = 20260917
ROWS = 12_000


def _generate(output: Path, *, chunk_size: int = 8) -> Path:
    config = build_config(load_parameters(), seed=SEED, total_rows=ROWS)
    generate(config, output, chunk_size=chunk_size, allow_missing_scenarios=True)
    return output


@pytest.fixture(scope="module")
def dataset(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return _generate(tmp_path_factory.mktemp("fingerprint"))


def test_the_fingerprint_is_stable_for_the_same_dataset(dataset: Path) -> None:
    """Nothing outside the rows enters it: no seed, no clock, no configuration."""
    assert dataset_fingerprint(dataset) == dataset_fingerprint(dataset)
    assert len(dataset_fingerprint(dataset)) == 64


def test_the_fingerprint_survives_a_different_chunk_size(
    dataset: Path, tmp_path_factory: pytest.TempPathFactory
) -> None:
    """E4 allows the chunk size to change and requires the dataset not to.

    A fingerprint that moved with the chunk size would fail on a legitimate difference, and would
    then be loosened — which is how a guard stops guarding. The sample is taken by value under a
    canonical ordering rather than by position for exactly this reason.
    """
    other = _generate(tmp_path_factory.mktemp("chunked"), chunk_size=2)
    assert dataset_fingerprint(other) == dataset_fingerprint(dataset), (
        "the fingerprint moved with the chunk size, so it is sampling by position"
    )


def test_a_code_change_that_re_draws_with_identical_parameters_is_detected(
    dataset: Path, tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch
) -> None:
    """PB-41's required mutation: a changed draw that the parameter digest cannot see.

    `_GOLDEN` is the low-discrepancy step the activity multipliers walk. It lives in generator
    **code**, not in the parameter set, so no provenance record covers it and `parameter_digest`
    is blind to it by construction — which is the point. Changing it moves every customer's
    activity multiplier, and with it the volume apportionment and every row drawn afterwards,
    while every parameter value stays byte-identical.

    That is PB-29's shape rather than its letter. PB-29 was a changed iteration order; the class
    is **anything outside the parameter set that steers the draw** — a code constant, an algorithm
    swapped for an equivalent one, a library's sampler changing between versions. A digest over
    inputs cannot cover that class, and no better digest over inputs would.
    """
    before = parameter_digest(load_parameters())
    monkeypatch.setattr(population, "_GOLDEN", math.sqrt(2.0) - 1.0)
    redrawn = _generate(tmp_path_factory.mktemp("redrawn"))

    # E12: asserted before the assertion it exists for. If the parameters had moved, the
    # difference below would be explained by them and would say nothing about the blind spot.
    assert parameter_digest(load_parameters()) == before, (
        "precondition: the mutation must leave every parameter value identical, or this test is "
        "about a parameter change"
    )
    assert dataset_fingerprint(redrawn) != dataset_fingerprint(dataset), (
        "the draw changed and the fingerprint did not move; that is the state PB-41 exists to "
        "make impossible"
    )


def test_the_parameter_digest_is_blind_to_that_change(
    dataset: Path, tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The other half, as its own test because it is the reason PB-41 was opened.

    If the parameter digest caught a code-level change to the draw, the fingerprint would be
    redundant and this module would be ceremony. It does not, and this is the evidence — stated
    positively rather than left as an inference from the test above.
    """
    monkeypatch.setattr(population, "_GOLDEN", math.sqrt(3.0) - 1.0)
    redrawn = _generate(tmp_path_factory.mktemp("blind"))

    assert dataset_fingerprint(redrawn) != dataset_fingerprint(dataset), (
        "precondition: the two datasets differ, or there is no blind spot to demonstrate"
    )
    assert parameter_digest(load_parameters()) == parameter_digest(load_parameters())
    committed = REALISM_REPORT_MD.read_text(encoding="utf-8")
    assert parameter_digest(load_parameters()) in committed, (
        "the committed report still matches the current parameters, so a reader checking only the "
        "parameter digest would conclude it describes this re-drawn dataset"
    )


def test_the_generator_no_longer_re_draws_when_the_pack_order_changes(
    dataset: Path, tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch
) -> None:
    """PB-29's own mechanism, measured rather than assumed — and it is closed.

    PB-29 re-drew the whole benchmark because `countries.simulated()` began returning packs in
    alphabetical rather than declaration order. Reversing that order today leaves the dataset
    **identical**, because `Population._apportioned` sorts the country share itself before
    apportioning and the merchant and agent tables are dicts keyed by country rather than
    sequences consumed in order.

    Pinned as a property, not recorded as a reassurance: if the internal sort is ever removed, pack
    order steers the draw again and PB-29 becomes possible a second time. This test is what would
    say so.

    It does **not** weaken PB-41. The fingerprint exists for the class of input-invisible changes,
    of which pack order was one instance that has since been closed; the test above shows the
    class is still populated.
    """
    original = countries.simulated

    def reversed_order(parameters: ParameterSet) -> dict[str, countries.CountryPack]:
        packs = original(parameters)
        return {code: packs[code] for code in reversed(list(packs))}

    monkeypatch.setattr("fraudshield_dataset.generator.config.simulated", reversed_order)
    config = build_config(load_parameters(), seed=SEED, total_rows=ROWS)
    assert config.countries == tuple(sorted(config.countries, reverse=True)), (
        "precondition: the mutation actually reached the config, or this test asserts that "
        "doing nothing changes nothing"
    )

    reordered = tmp_path_factory.mktemp("reordered")
    generate(config, reordered, allow_missing_scenarios=True)
    assert dataset_fingerprint(reordered) == dataset_fingerprint(dataset)


def test_every_table_contributes_and_a_missing_one_is_refused(
    dataset: Path, tmp_path: Path
) -> None:
    """A fingerprint that quietly covered two tables of three would still look like a fingerprint.

    So a missing table is refused rather than skipped. The precondition asserts each table is
    non-empty first, or "a missing table changes the fingerprint" would hold vacuously.
    """
    for table in ("transactions", "labels", "account_events"):
        assert table_fingerprint(dataset, table)["rows"] > 0, f"precondition: {table} has rows"

    partial = tmp_path / "partial"
    shutil.copytree(dataset, partial)
    shutil.rmtree(partial / "account_events")
    with pytest.raises(FingerprintError, match="nothing to fingerprint"):
        dataset_fingerprint(partial)


def test_a_change_confined_to_one_table_moves_the_fingerprint(dataset: Path) -> None:
    """Each table's contribution is separable, so a change in any of them is detectable.

    Asserted on the payload rather than by regenerating three datasets: the three contributions are
    distinct values in the hashed structure, so no one of them can be silently dropped.
    """
    contributions = {
        table: table_fingerprint(dataset, table)
        for table in ("transactions", "labels", "account_events")
    }
    samples = [tuple(map(tuple, c["sample"])) for c in contributions.values()]
    assert len(set(samples)) == 3, "two tables contributed the same sample"
    for table, contribution in contributions.items():
        assert contribution["sample"], f"{table} contributed an empty sample"
        assert len(contribution["sample"]) <= SAMPLE_ROWS


def test_the_sample_is_the_first_rows_under_a_total_order(dataset: Path) -> None:
    """The sample must be sorted and deduplicated by nothing: a set would lose multiplicity.

    Account events repeat an account id, so the ordering key is the whole row rather than the
    identifier. If it were the identifier alone, ties would break on arrival order and the
    fingerprint would depend on partition layout after all.
    """
    events = table_fingerprint(dataset, "account_events")["sample"]
    assert events == sorted(events), "the sample is not in canonical order"
    identifiers = [row[0] for row in events]
    assert len(identifiers) > len(set(identifiers)), (
        "precondition: an account id repeats in the sample, which is what makes a whole-row "
        "ordering key necessary rather than tidy"
    )


@pytest.mark.req("ML-DATA-08")
def test_every_column_of_a_sampled_row_reaches_the_fingerprint(
    dataset: Path, tmp_path: Path
) -> None:
    """The defect version 1 had, as a test that fails against version 1 and passes against 2.

    PB-40 gave the generator a device-sharing mechanism. It rewrote `device_fingerprint` for
    hundreds of accounts and changed nothing else — and the fingerprint **did not move**, because
    the sample hashed three columns per table and `device_fingerprint` was not one of them. A
    guard that reads three columns of fourteen cannot answer "is this report still about this
    dataset?", which is the only question it exists to answer.

    The assertion is per column, over every column the table has rather than over a chosen few:
    choosing which columns to check would reproduce the original mistake inside the test written
    to prevent it.

    The row perturbed is one the sample actually contains. The first attempt at this test edited
    row 0 of the first partition and failed for all fourteen columns — the sample is the 1,024
    rows that sort first by identifier, and row 0 of a partition is almost never among them. A
    test that edits an unsampled row proves nothing about any column.
    """
    original = dataset_fingerprint(dataset)
    sampled_id = table_fingerprint(dataset, "transactions")["sample"][0][0]

    for partition in sorted((dataset / "transactions").glob("month=*/*.parquet")):
        table = pq.read_table(partition)
        identifiers = table.column("transaction_id").to_pylist()
        if sampled_id in identifiers:
            row = identifiers.index(sampled_id)
            break
    else:
        raise AssertionError(f"{sampled_id} is in the sample but in no partition")

    unmoved = []
    for column in table.schema.names:
        changed = tmp_path / f"only-{column}"
        if changed.exists():
            shutil.rmtree(changed)
        shutil.copytree(dataset, changed)
        kind = table.schema.field(column).type
        values = table.column(column).to_pylist()
        values[row] = _perturb(kind, values[row])
        edited = table.set_column(
            table.schema.get_field_index(column), column, pa.array(values, type=kind)
        )
        pq.write_table(edited, changed / partition.relative_to(dataset))
        if dataset_fingerprint(changed) == original:
            unmoved.append(column)

    assert not unmoved, (
        f"changing {unmoved} in a sampled row leaves the fingerprint identical, so a draw that "
        "moved only those columns would ship under a report that no longer describes it"
    )


def _perturb(kind: pa.DataType, value: object) -> object:
    """A different value of the same type, so the edit is a change and not a type error.

    Every Arrow type the tables use has a case, and an unknown one raises rather than returning
    the value unchanged: a silent no-op would leave that column untested while the loop above
    reported it as passing.
    """
    if pa.types.is_string(kind):
        return "perturbed" if value != "perturbed" else "perturbed-2"
    if pa.types.is_decimal(kind):
        return (value if isinstance(value, Decimal) else Decimal(0)) + Decimal("1.0000")
    if pa.types.is_timestamp(kind):
        base = value if isinstance(value, datetime) else datetime(2024, 1, 1, tzinfo=UTC)
        return base + timedelta(seconds=1)
    if pa.types.is_boolean(kind):
        return not value
    if pa.types.is_floating(kind):
        return (value if isinstance(value, float) else 0.0) + 1.0
    if pa.types.is_integer(kind):
        return (value if isinstance(value, int) else 0) + 1
    raise AssertionError(f"no perturbation defined for {kind}, so the column would go unchecked")
