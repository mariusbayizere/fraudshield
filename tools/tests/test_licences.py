from __future__ import annotations

import json
from pathlib import Path

import pytest

from fraudshield_tools import licences
from fraudshield_tools.licences import (
    Dependency,
    InventoryError,
    parse_third_party_report,
    violations,
)


def _dep(scope: str, *declared: str) -> Dependency:
    return Dependency("maven", "org.example:lib", "1.0", scope, declared, "all")


def test_policy_by_scope() -> None:
    assert violations([_dep("dev", "Eclipse Public License v2.0")]) == []
    assert violations([_dep("runtime", "Apache License, Version 2.0")]) == []
    assert violations([_dep("runtime", "Eclipse Public License v2.0")]) == []  # ADR 0020
    runtime_lgpl = violations([_dep("runtime", "GNU Lesser General Public License v3 (LGPLv3)")])
    assert len(runtime_lgpl) == 1
    assert "not allowed for runtime" in runtime_lgpl[0]
    assert "not allowed" in violations([_dep("dev", "GNU Affero General Public License v3")])[0]
    assert "unidentified" in violations([_dep("dev")])[0]


REPORT = "\n".join(
    [
        "Lists of 2 third-party dependencies.",
        "     (BSD) (The Apache Software License, Version 2.0) ArchUnit"
        " (com.tngtech.archunit:archunit:1.5.0 - https://github.com/TNG/ArchUnit)",
        "     (Eclipse Public License v2.0) JUnit Jupiter (Aggregator)"
        " (org.junit.jupiter:junit-jupiter:6.0.3 - https://junit.org/)",
    ]
)


def test_parse_third_party_report_verifies_count() -> None:
    parsed = parse_third_party_report(REPORT)
    assert parsed == {
        "com.tngtech.archunit:archunit:1.5.0": ("BSD", "The Apache Software License, Version 2.0"),
        "org.junit.jupiter:junit-jupiter:6.0.3": ("Eclipse Public License v2.0",),
    }
    assert parse_third_party_report("The project has no dependencies.\n") == {}
    with pytest.raises(InventoryError, match="declares 3"):
        parse_third_party_report(REPORT.replace("Lists of 2", "Lists of 3"))
    with pytest.raises(InventoryError):
        parse_third_party_report("unexpected output")


def test_collectors_classify_scope(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    report_dir = tmp_path / "backend/target/generated-sources/license"
    runtime_line = "     (MIT) SLF4J API Module (org.slf4j:slf4j-api:2.0.18 - http://www.slf4j.org)"

    def fake_run(args: list[str], cwd: Path) -> str:
        command = " ".join(args)
        if args[0] == "uv":
            return "# exported\npyyaml==6.0.3\n"
        if args[0] == "pnpm":
            prod = "--prod" in args
            packages = {"MIT": [{"name": "react", "versions": ["19.3.0"]}]}
            if not prod:
                packages["Apache-2.0"] = [{"name": "typescript", "versions": ["6.0.3"]}]
            return json.dumps(packages)
        filename = next(a.split("=", 1)[1] for a in args if a.startswith("-Dlicense.thirdParty"))
        report_dir.mkdir(parents=True, exist_ok=True)
        lines = ["Lists of 1 third-party dependencies.", runtime_line]
        if "test" in command:
            lines[0] = "Lists of 3 third-party dependencies."
            lines.extend(REPORT.splitlines()[1:])
        (report_dir / filename).write_text("\n".join(lines))
        assert cwd == tmp_path / "backend"
        return ""

    monkeypatch.setattr(licences, "_run", fake_run)

    python = {d.name.lower(): d for d in licences.python_dependencies(tmp_path)}
    assert python["pyyaml"].scope == "runtime"
    assert python["pytest"].scope == "dev"
    assert "fraudshield-tools" not in python

    node = {d.name: d.scope for d in licences.node_dependencies(tmp_path)}
    assert node == {"react": "runtime", "typescript": "dev"}

    java = {d.name: d.scope for d in licences.java_dependencies(tmp_path)}
    assert java == {
        "org.slf4j:slf4j-api": "runtime",
        "com.tngtech.archunit:archunit": "dev",
        "org.junit.jupiter:junit-jupiter": "dev",
    }


def test_main_writes_inventory_and_fails_on_violation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    deps = [_dep("dev", "MIT License")]
    monkeypatch.setattr(licences, "python_dependencies", lambda _root: deps)
    monkeypatch.setattr(licences, "node_dependencies", lambda _root: [])
    monkeypatch.setattr(licences, "java_dependencies", lambda _root: [])
    output = tmp_path / "inventory.json"
    assert licences.main(["--output", str(output)]) == 0
    assert json.loads(output.read_text())[0]["name"] == "org.example:lib"
    deps.append(_dep("runtime", "GNU Lesser General Public License v3 (LGPLv3)"))
    assert licences.main([]) == 1


def _no_collection(monkeypatch: pytest.MonkeyPatch, dependencies: list[Dependency]) -> None:
    """Patch out the three collectors, which shell out to Maven and pnpm and take two minutes."""

    monkeypatch.setattr(licences, "python_dependencies", lambda _root: list(dependencies))
    monkeypatch.setattr(licences, "node_dependencies", lambda _root: [])
    monkeypatch.setattr(licences, "java_dependencies", lambda _root: [])


@pytest.mark.req("OPS-CI-04")
def test_an_empty_scope_is_reported_as_carrying_no_information(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """ADR 0009, 2026-09-19: a check that has only ever run against an empty scope is untested.

    From M0 until h3 landed, the Python runtime scope was empty. The licence check passed on every
    commit through three milestones and those passes said nothing — an empty input satisfies almost
    any predicate, and a green check over nothing is indistinguishable in CI output from a green
    check over something. The first real input failed it.

    So an empty scope now says so, loudly, in its own line.
    """

    _no_collection(monkeypatch, [])
    assert licences.main([]) == 0, "precondition: an empty inventory has no violations to find"
    captured = capsys.readouterr()
    assert "the runtime scope is empty" in captured.err
    assert "the dev scope is empty" in captured.err
    assert "had nothing to check" in captured.err


@pytest.mark.req("OPS-CI-04")
def test_a_populated_scope_does_not_warn(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """The control: a warning that always fires is noise, and would be filtered within a week."""
    populated = [
        Dependency(
            ecosystem="python",
            name="example",
            version="1.0.0",
            scope=scope,
            declared=("MIT",),
            combine="any",
        )
        for scope in ("runtime", "dev")
    ]
    _no_collection(monkeypatch, populated)
    licences.main([])
    captured = capsys.readouterr()
    assert "scope is empty" not in captured.err, (
        "neither scope is empty in this fixture, so neither should warn"
    )
