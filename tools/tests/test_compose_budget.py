from __future__ import annotations

import pytest
import yaml

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.compose_budget import PROFILE_BUDGETS_MIB, BudgetError, check, to_mib


def test_repository_compose_file_fits_budgets() -> None:
    compose = yaml.safe_load((REPO_ROOT / "docker-compose.yml").read_text(encoding="utf-8"))
    totals, errors = check(compose)
    assert errors == []
    assert totals["core"] == 3_968  # recorded in docs/benchmarks/hardware.md
    assert totals["core"] <= PROFILE_BUDGETS_MIB["core"]


@pytest.mark.parametrize(
    ("raw", "mib"), [("768m", 768), ("1g", 1024), ("1.5g", 1536), ("512MB", 512)]
)
def test_units(raw: str, mib: float) -> None:
    assert to_mib(raw) == mib


def test_unparseable_limit() -> None:
    with pytest.raises(BudgetError):
        to_mib("lots")


def test_missing_limit_unknown_profile_and_overflow() -> None:
    compose = {
        "services": {
            "a": {"profiles": ["core"]},
            "b": {"profiles": ["gpu"], "deploy": {"resources": {"limits": {"memory": "64m"}}}},
            "c": {
                "profiles": ["core", "full"],
                "deploy": {"resources": {"limits": {"memory": "5g"}}},
            },
        }
    }
    _totals, errors = check(compose)
    assert "service a has no deploy.resources.limits.memory" in errors
    assert "service b uses profiles without a budget: ['gpu']" in errors
    assert any("profile core: memory limits total 5120 MiB exceed" in e for e in errors)
