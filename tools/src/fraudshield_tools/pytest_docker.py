"""pytest plugin: ``@pytest.mark.requires_docker`` for Docker-dependent tests (ADR 0010).

* Docker reachable: the tests run.
* Docker unreachable: the tests are skipped with an explicit reason, and the terminal summary
  prints ``SKIPPED: N tests require Docker, verified in CI`` so the skip is never silent.
* Docker unreachable and ``REQUIRE_DOCKER`` set (CI and Codespaces): the session fails.

``FS_DOCKER_AVAILABLE=0|1`` overrides detection; it exists for this plugin's own tests.
"""

from __future__ import annotations

import os
import subprocess

import pytest

MARKER = "requires_docker"
SKIP_REASON = "SKIPPED: requires Docker, verified in CI (REQUIRE_DOCKER=1 fails instead)"
DOCKER_INFO_TIMEOUT_SECONDS = 10
_skipped_key = pytest.StashKey[int]()


def docker_available() -> bool:
    override = os.environ.get("FS_DOCKER_AVAILABLE")
    if override in {"0", "1"}:
        return override == "1"
    try:
        result = subprocess.run(
            ["docker", "info"],  # noqa: S607 - fixed command
            capture_output=True,
            timeout=DOCKER_INFO_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", f"{MARKER}: needs a reachable Docker daemon (ADR 0010)")


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    docker_items = [item for item in items if item.get_closest_marker(MARKER)]
    config.stash[_skipped_key] = 0
    if not docker_items or docker_available():
        return
    if os.environ.get("REQUIRE_DOCKER"):
        raise pytest.UsageError(
            f"{len(docker_items)} test(s) require Docker; REQUIRE_DOCKER is set but no Docker "
            "daemon is reachable"
        )
    for item in docker_items:
        item.add_marker(pytest.mark.skip(reason=SKIP_REASON))
    config.stash[_skipped_key] = len(docker_items)


def pytest_terminal_summary(
    terminalreporter: pytest.TerminalReporter, config: pytest.Config
) -> None:
    skipped = config.stash.get(_skipped_key, 0)
    if skipped:
        terminalreporter.write_line(
            f"SKIPPED: {skipped} test(s) require Docker, verified in CI", yellow=True, bold=True
        )
