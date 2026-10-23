"""E15's guard, tested. A guard with no test passes every clean run and proves nothing."""

from __future__ import annotations

from pathlib import Path

import pytest

from .shared_state_guard import describe_changes, tree_digest


@pytest.fixture
def tree(tmp_path: Path) -> Path:
    root = tmp_path / "params"
    (root / "countries").mkdir(parents=True)
    (root / "geography.yaml").write_text("country_share:\n  RW: 1.0\n", encoding="utf-8")
    (root / "countries" / "RW.yaml").write_text("currency: RWF\n", encoding="utf-8")
    return root


@pytest.mark.req("ML-DATA-07")
def test_an_unchanged_tree_reports_nothing(tree: Path, tmp_path: Path) -> None:
    """The control. A guard that reports on a clean tree would be turned off within a day."""
    before = tree_digest(tree, tmp_path)
    assert before, "precondition: the fixture tree contains files to digest"
    assert describe_changes(before, tree_digest(tree, tmp_path)) == []


@pytest.mark.req("ML-DATA-07")
def test_the_geography_mutation_that_broke_a_real_run_is_reported(
    tree: Path, tmp_path: Path
) -> None:
    """The exact failure E15 was written for, reproduced inside tmp_path.

    A test edited the repository's geography.yaml and restored it in a `finally` that did not run
    when the body raised. The suite then ran against a country share naming a pack that did not
    exist, and announced itself as an unrelated failure elsewhere.
    """
    before = tree_digest(tree, tmp_path)
    (tree / "geography.yaml").write_text("country_share:\n  ZZ: 1.0\n", encoding="utf-8")
    changes = describe_changes(before, tree_digest(tree, tmp_path))
    assert changes == ["modified: params/geography.yaml"]


@pytest.mark.req("ML-DATA-07")
def test_a_created_pack_is_reported_because_the_loader_globs_the_directory(
    tree: Path, tmp_path: Path
) -> None:
    """Creation matters as much as modification, and only since the country packs landed.

    `load_parameters` globs `countries/*.yaml`, so writing a new pack into the tree adds a country
    without editing a single existing file. A guard watching only for modifications would miss it.
    """
    before = tree_digest(tree, tmp_path)
    (tree / "countries" / "ZZ.yaml").write_text("currency: ZZZ\n", encoding="utf-8")
    changes = describe_changes(before, tree_digest(tree, tmp_path))
    assert changes == ["created:  params/countries/ZZ.yaml"]


@pytest.mark.req("ML-DATA-07")
def test_a_deleted_file_is_reported(tree: Path, tmp_path: Path) -> None:
    before = tree_digest(tree, tmp_path)
    (tree / "countries" / "RW.yaml").unlink()
    changes = describe_changes(before, tree_digest(tree, tmp_path))
    assert changes == ["deleted:  params/countries/RW.yaml"]


@pytest.mark.req("ML-DATA-07")
def test_a_missing_tree_digests_empty_rather_than_raising(tmp_path: Path) -> None:
    """The guard's own precondition assertion catches a moved tree, not an exception here."""
    assert tree_digest(tmp_path / "does-not-exist", tmp_path) == {}
