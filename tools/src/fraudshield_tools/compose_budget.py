"""Memory budget per docker-compose profile (ADR 0010).

Every service must declare ``deploy.resources.limits.memory``; the sum of limits of the services
in each profile must fit that profile's budget. Budgets are sized for the Codespaces machine
(4 cores / 16 GB), leaving room for the IDE, Maven and Gradle daemons, pnpm and test JVMs.
"""

from __future__ import annotations

import re
import sys
from typing import Any

import yaml

from fraudshield_tools import REPO_ROOT

MIB_PER_UNIT = {"b": 1 / 1024**2, "k": 1 / 1024, "m": 1, "g": 1024}
# Budgets in MiB. `ml` and `obs` services arrive in M5 and M9; `full` is everything together.
PROFILE_BUDGETS_MIB: dict[str, int] = {
    "core": 4_096,
    "ml": 6_144,
    "obs": 5_120,
    "full": 10_240,
}


class BudgetError(ValueError):
    """Raised for unparseable memory limits."""


def to_mib(limit: str) -> float:
    match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([bkmg])b?", str(limit).strip().lower())
    if match is None:
        raise BudgetError(f"unparseable memory limit {limit!r}")
    return float(match.group(1)) * MIB_PER_UNIT[match.group(2)]


def check(compose: dict[str, Any]) -> tuple[dict[str, float], list[str]]:
    totals = dict.fromkeys(PROFILE_BUDGETS_MIB, 0.0)
    errors: list[str] = []
    for name, service in (compose.get("services") or {}).items():
        limit = (((service.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get(
            "memory"
        )
        profiles = service.get("profiles") or []
        if limit is None:
            errors.append(f"service {name} has no deploy.resources.limits.memory")
            continue
        unknown = [p for p in profiles if p not in PROFILE_BUDGETS_MIB]
        if unknown:
            errors.append(f"service {name} uses profiles without a budget: {unknown}")
        for profile in profiles:
            if profile in totals:
                totals[profile] += to_mib(limit)
    for profile, total in totals.items():
        if total > PROFILE_BUDGETS_MIB[profile]:
            errors.append(
                f"profile {profile}: memory limits total {total:.0f} MiB "
                f"exceed the {PROFILE_BUDGETS_MIB[profile]} MiB budget"
            )
    return totals, errors


def main() -> int:
    compose = yaml.safe_load((REPO_ROOT / "docker-compose.yml").read_text(encoding="utf-8"))
    totals, errors = check(compose)
    for error in errors:
        print(f"ERROR {error}", file=sys.stderr)
    summary = ", ".join(
        f"{p} {totals[p]:.0f}/{PROFILE_BUDGETS_MIB[p]} MiB" for p in PROFILE_BUDGETS_MIB
    )
    print(f"compose-budget: {summary}; {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
