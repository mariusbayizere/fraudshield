"""The gitleaks self-test may only plant secrets the scanner actually reports.

gitleaks' own rule allowlists skip a value that is all letters, or that contains a stopword
substring such as ``http``, ``text`` or ``rail``. Planting one of those made the self-test fail
about 4% of runs, which is a flake in every consumer's CI (lab notebook, 2026-09-24). These tests
pin the two defences: a digit in every generated value, and a probe scan that regenerates anything
the scanner would skip.
"""

from __future__ import annotations

import importlib.machinery
import importlib.util
import string
import sys
from pathlib import Path
from types import ModuleType

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "bin" / "gitleaks-selftest"


def load() -> ModuleType:
    """Import the script, which has no .py suffix and is not a package module."""
    spec = importlib.util.spec_from_loader(
        "gitleaks_selftest",
        importlib.machinery.SourceFileLoader("gitleaks_selftest", str(SCRIPT)),
    )
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["gitleaks_selftest"] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


# After the shared "tok_" prefix, letters and digits alternate, so no stopword of three letters or
# more can occur in the body. The value is fixed, so for a given gitleaks binary and config it is
# reported on every run or on none; a gitleaks bump that stopped reporting it fails the control
# loudly instead of flaking.
GOOD = "tok_7Q9z3K8w2M4x6B1v5N0c7P2d"


@pytest.fixture(scope="module")
def selftest() -> ModuleType:
    if not (ROOT / "tools" / "bin" / "gitleaks").exists():
        pytest.skip("pinned gitleaks binary is not installed")
    return load()


def test_values_shaped_like_the_planted_ones_remain_reportable(selftest: ModuleType) -> None:
    """Convergence: 120 values drawn as the self-test draws them all end up reportable.

    fresh_secrets already guarantees this for what it returns, so this test does not prove the
    regeneration; ``test_a_value_the_scanner_skips_is_regenerated`` does, deterministically. What it
    proves is that the scanner still reports this shape at all: a widened allowlist or a rule change
    that skipped every ``tok_`` value would make fresh_secrets exhaust its attempts and fail here.
    """

    def draw() -> str:
        return f"tok_{selftest.random_text(selftest.ALNUM, 24, digit=True)}"

    values = selftest.fresh_secrets({f"tok{index:03d}": draw for index in range(120)})
    assert selftest.detectable(values) == set(values)


def test_the_probe_can_fail_so_the_check_is_not_vacuous(selftest: ModuleType) -> None:
    """Control: values gitleaks is known to skip must come back as not detected.

    Without this, a probe that reported everything as detectable would pass the test above while
    checking nothing.
    """
    letters_only = "tok_" + selftest.random_text(string.ascii_letters, 24)
    stopword = "tok_" + f"httpA1{selftest.random_text(string.ascii_letters, 18)}"
    good = GOOD
    found = selftest.detectable({"letters": letters_only, "stop": stopword, "good": good})
    assert found == {"good"}


def test_a_value_the_scanner_skips_is_regenerated(selftest: ModuleType) -> None:
    """fresh_secrets must replace an undetectable value rather than plant it."""
    drawn: list[str] = []

    def undetectable_then_good() -> str:
        drawn.append("call")
        if len(drawn) == 1:
            return "tok_" + str(selftest.random_text(string.ascii_letters, 24))  # all letters
        return GOOD

    values = selftest.fresh_secrets({"key": undetectable_then_good})
    assert len(drawn) > 1, "the first, undetectable value was planted"
    assert selftest.detectable(values) == {"key"}


def test_random_text_plants_a_digit(selftest: ModuleType) -> None:
    """An all-letter value matches a gitleaks allowlist, so digit=True must guarantee one."""
    for _ in range(200):
        assert any(c.isdigit() for c in selftest.random_text(selftest.ALNUM, 24, digit=True))
    assert len(selftest.random_text(selftest.ALNUM, 24, digit=True)) == 24
