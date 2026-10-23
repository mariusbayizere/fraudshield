"""ADR 0019 point 5: the synthetic-data guard runs in every Spring Boot application.

The guards are registered by ``backend/deployment-guard``'s ``spring.factories``, so an application
runs them only if its module depends on that module. Until M9 the API did not, and the ADR's claim
held for the database tool alone (amendment of 2026-10-22). This check covers the set: every module
with a ``@SpringBootApplication`` under ``src/main`` must declare the dependency.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from fraudshield_tools import REPO_ROOT

pytestmark = pytest.mark.req("D-21")

BACKEND = REPO_ROOT / "backend"
GUARD = "<artifactId>fraudshield-deployment-guard</artifactId>"


def application_modules() -> list[Path]:
    modules = set()
    for source in BACKEND.glob("*/src/main/java/**/*.java"):
        if re.search(r"^@SpringBootApplication\b", source.read_text(encoding="utf-8"), re.M):
            modules.add(source.relative_to(BACKEND).parts[0])
    return [BACKEND / name for name in sorted(modules)]


def test_the_check_sees_both_known_applications() -> None:
    names = {module.name for module in application_modules()}
    assert {"ingest", "persistence"} <= names


@pytest.mark.parametrize("module", application_modules(), ids=lambda module: module.name)
def test_every_spring_boot_application_depends_on_the_guards(module: Path) -> None:
    assert GUARD in (module / "pom.xml").read_text(encoding="utf-8"), (
        f"backend/{module.name} has a @SpringBootApplication but does not depend on"
        " fraudshield-deployment-guard, so it would start without ADR 0019's guards"
    )
