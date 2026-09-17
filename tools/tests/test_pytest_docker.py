from __future__ import annotations

import pytest

pytest_plugins = ["pytester"]

SUITE = """
import pytest

@pytest.mark.requires_docker
def test_needs_docker():
    assert True

def test_plain():
    assert True
"""


def test_runs_docker_tests_when_docker_is_available(
    pytester: pytest.Pytester, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FS_DOCKER_AVAILABLE", "1")
    pytester.makepyfile(SUITE)
    result = pytester.runpytest_subprocess()
    result.assert_outcomes(passed=2)


def test_skips_loudly_without_docker(
    pytester: pytest.Pytester, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FS_DOCKER_AVAILABLE", "0")
    monkeypatch.delenv("REQUIRE_DOCKER", raising=False)
    pytester.makepyfile(SUITE)
    result = pytester.runpytest_subprocess("-rs")
    result.assert_outcomes(passed=1, skipped=1)
    result.stdout.fnmatch_lines(
        [
            "SKIPPED: 1 test(s) require Docker, verified in CI",
            "*SKIPPED: requires Docker, verified in CI*",
        ]
    )


def test_fails_without_docker_when_required(
    pytester: pytest.Pytester, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FS_DOCKER_AVAILABLE", "0")
    monkeypatch.setenv("REQUIRE_DOCKER", "1")
    pytester.makepyfile(SUITE)
    result = pytester.runpytest_subprocess()
    assert result.ret != 0
    result.stderr.fnmatch_lines(["*REQUIRE_DOCKER is set but no Docker daemon is reachable*"])


def test_detection_without_override_does_not_crash(monkeypatch: pytest.MonkeyPatch) -> None:
    from fraudshield_tools.pytest_docker import docker_available  # noqa: PLC0415

    monkeypatch.delenv("FS_DOCKER_AVAILABLE", raising=False)
    assert docker_available() in {True, False}
