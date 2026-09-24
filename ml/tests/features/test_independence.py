"""Parity Decision 4: the two paths must be independent implementations.

A parity test is blind to any bug both paths share. If the online path is the batch path behind a
different entry point, the test proves only that a function equals itself, and its value is exactly
the size of the surface the two do not share.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

import fraudshield_ml.features as _features_pkg
from fraudshield_ml.features import primitives
from fraudshield_ml.features.registry import SHARED_PRIMITIVES

FEATURES = Path(_features_pkg.__file__).resolve().parent

#: Modules both paths may import: declarative contract, pure data records, and the enumerated
#: primitives. None of the first two computes anything.
PERMITTED_SHARED = {"registry", "types", "primitives"}


def _imports(module: str) -> set[str]:
    """Every `fraudshield_ml.features.*` module imported by `module`, directly."""
    tree = ast.parse((FEATURES / f"{module}.py").read_text(encoding="utf-8"))
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            if node.module.startswith("fraudshield_ml.features."):
                found.add(node.module.rsplit(".", 1)[1])
        elif isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.startswith("fraudshield_ml.features."):
                    found.add(alias.name.rsplit(".", 1)[1])
    return found


@pytest.mark.req("FR-02-02")
def test_the_two_paths_do_not_import_each_other() -> None:
    """The enforcement, not the intention."""
    batch_imports = _imports("batch")
    online_imports = _imports("online")
    assert batch_imports, "precondition: the batch module imports something from the package"
    assert online_imports, "precondition: the online module imports something from the package"
    assert "online" not in batch_imports, "the batch path imports the online path"
    assert "batch" not in online_imports, "the online path imports the batch path"


@pytest.mark.req("FR-02-02")
def test_neither_path_imports_anything_outside_the_permitted_shared_surface() -> None:
    """A new shared module is a decision, not an accident.

    Adding one silently is how the shared surface grows until the parity test covers nothing. This
    fails on any new `features.*` import so the addition has to be argued for.
    """
    for module in ("batch", "online"):
        extra = _imports(module) - PERMITTED_SHARED
        assert not extra, (
            f"{module} imports {sorted(extra)} from the features package. Only "
            f"{sorted(PERMITTED_SHARED)} may be shared: the first two compute nothing, and "
            "primitives is enumerated in SHARED_PRIMITIVES with its own hand-computed tests"
        )


@pytest.mark.req("FR-02-02")
def test_the_shared_primitives_are_the_ones_the_registry_declares() -> None:
    """The registry's list and the module must not drift apart."""
    declared = set(SHARED_PRIMITIVES)
    implemented = {
        n for n in vars(primitives) if not n.startswith("_") and callable(getattr(primitives, n))
    } - {"h3"}
    assert declared, "precondition: the registry declares at least one shared primitive"
    missing = declared - implemented
    assert not missing, f"registry declares {sorted(missing)} but primitives does not implement it"


@pytest.mark.req("FR-02-02")
def test_no_shared_window_aggregation_helper_exists() -> None:
    """Explicitly forbidden by parity Decision 4.

    Window aggregation is precisely what the parity test checks. A shared helper would make both
    paths agree by construction, and the test would prove a function equals itself — passing
    loudest exactly where it should be catching something.
    """
    names = [n for n in vars(primitives) if not n.startswith("_")]
    for banned in ("window", "rolling", "trailing", "aggregate", "count_between"):
        offenders = [n for n in names if banned in n.lower()]
        assert not offenders, (
            f"primitives exports {offenders}, which looks like shared window aggregation; that is "
            "the one surface the parity test cannot cover"
        )


@pytest.mark.req("FR-02-02")
def test_neither_feature_path_imports_the_vector_assembler() -> None:
    """`vector` calls `batch`; `batch` must never call `vector`.

    A feature path importing its own caller would make the parity test circular — the assembler
    exists to run the paths, and a path that reached back into it could reach the other one
    through it. The rule is not "one direction is tidier": it is that Decision 4's guarantee is
    exactly the size of the surface the two paths do not share, and this would be a back door
    into it.
    """
    for module in ("batch", "online"):
        assert "vector" not in _imports(module), (
            f"{module} imports the vector assembler, which is its caller; the parity suite's "
            "independence guarantee runs through that import"
        )
