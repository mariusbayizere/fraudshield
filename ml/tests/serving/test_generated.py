"""The checked-in message classes are exactly what the frozen contract compiles to."""

from __future__ import annotations

from pathlib import Path

import pytest

from fraudshield_ml.serving.generated import regenerate

REPOSITORY = Path(__file__).resolve().parents[3]


@pytest.mark.req("FR-02-01")
def test_the_checked_in_stubs_match_the_contract(tmp_path: Path) -> None:
    fresh = regenerate.compile_to(REPOSITORY / "contracts" / "proto", tmp_path)
    for name, text in fresh.items():
        checked_in = (Path(regenerate.__file__).parent / name).read_text()
        assert checked_in == text, (
            f"{name} differs from contracts/proto; run "
            "python -m fraudshield_ml.serving.generated.regenerate"
        )


def test_the_header_keeps_the_formatter_away() -> None:
    assert regenerate.HEADER.startswith("# ruff: noqa\n# fmt: off\n")
