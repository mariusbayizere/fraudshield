from __future__ import annotations

import os
import re
import stat
import subprocess
from pathlib import Path

import pytest

from fraudshield_tools import REPO_ROOT, seed_demo

pytestmark = pytest.mark.req("D-21", "FR-05-07")

GITLEAKS_API_KEY = re.compile(r"\bfsk_(?:dev|test|stg|prod)_[a-z0-9]{12}_[A-Za-z0-9_-]{43}")


def test_passwords_meet_the_policy_and_differ() -> None:
    passwords = {seed_demo.generate_password() for _ in range(50)}
    assert len(passwords) == 50
    for password in passwords:
        assert len(password) == seed_demo.PASSWORD_LENGTH
        assert re.search(r"[A-Z]", password)
        assert re.search(r"[a-z]", password)
        assert re.search(r"[0-9]", password)
        assert any(c in seed_demo.SPECIALS for c in password)
        assert set(password) <= set(seed_demo.PASSWORD_ALPHABET)


def test_short_passwords_are_refused() -> None:
    with pytest.raises(ValueError, match="12"):
        seed_demo.generate_password(11)


def test_api_key_has_the_development_format_the_scanner_recognises() -> None:
    key = seed_demo.generate_api_key()
    assert re.fullmatch(r"fsk_dev_[a-z0-9]{12}_[A-Za-z0-9_-]{43}", key)
    assert GITLEAKS_API_KEY.search(key)


def test_first_run_creates_a_private_file_and_later_runs_reuse_it(tmp_path: Path) -> None:
    path = tmp_path / ".demo-credentials"
    created, was_created = seed_demo.load_or_create(path)
    assert was_created
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert set(created) == set(seed_demo.REQUIRED)
    assert len(bytes.fromhex(created[seed_demo.PEPPER])) == 32
    again, was_created = seed_demo.load_or_create(path)
    assert not was_created
    assert again == created


def test_existing_file_is_never_overwritten(tmp_path: Path) -> None:
    path = tmp_path / ".demo-credentials"
    path.write_text("keep", encoding="utf-8")
    with pytest.raises(FileExistsError):
        seed_demo.write_new(path, seed_demo.generate())
    assert path.read_text(encoding="utf-8") == "keep"


def test_group_or_world_readable_file_is_refused(tmp_path: Path) -> None:
    path = tmp_path / ".demo-credentials"
    seed_demo.write_new(path, seed_demo.generate())
    path.chmod(0o644)
    with pytest.raises(seed_demo.SeedDemoError, match="chmod 600"):
        seed_demo.load_or_create(path)


def test_incomplete_or_malformed_file_is_refused(tmp_path: Path) -> None:
    path = tmp_path / ".demo-credentials"
    os.close(os.open(path, os.O_WRONLY | os.O_CREAT, 0o600))
    path.write_text("# only a comment\nFRAUDSHIELD_DEMOSEED_APIKEY=x\n", encoding="utf-8")
    with pytest.raises(seed_demo.SeedDemoError, match="missing"):
        seed_demo.load_or_create(path)
    path.write_text("no separator\n", encoding="utf-8")
    with pytest.raises(seed_demo.SeedDemoError, match="malformed"):
        seed_demo.load_or_create(path)


def test_announcement_lists_every_account_and_the_banner() -> None:
    credentials = seed_demo.generate()
    text = seed_demo.announcement(credentials, Path(".demo-credentials"))
    assert "SYNTHETIC DATA — NOT FOR PRODUCTION" in text
    for name in seed_demo.REQUIRED[:-1]:
        assert credentials[name] in text
    assert credentials[seed_demo.PEPPER] not in text


def test_migrator_password_comes_from_the_environment_or_dot_env(tmp_path: Path) -> None:
    env_file = tmp_path / ".env"
    assert seed_demo.migrator_password({"FRAUDSHIELD_DB_MIGRATOR_PASSWORD": "a"}, env_file) == "a"
    with pytest.raises(seed_demo.SeedDemoError, match="make env"):
        seed_demo.migrator_password({}, env_file)
    env_file.write_text("FS_MIGRATOR_DB_PASSWORD=b\n", encoding="utf-8")
    assert seed_demo.migrator_password({}, env_file) == "b"


def test_child_environment_selects_the_demo_profile_and_drops_inherited_settings() -> None:
    credentials = seed_demo.generate()
    environment = seed_demo.child_environment(
        {"PATH": "/bin", "FRAUDSHIELD_DEMOSEED_APIKEY": "inherited", "SPRING_PROFILES_ACTIVE": "x"},
        credentials,
        "jdbc:postgresql://db/x",
        "migrator-secret",
    )
    assert environment["PATH"] == "/bin"
    assert environment["SPRING_PROFILES_ACTIVE"] == "demo"
    assert environment["FRAUDSHIELD_DEMOSEED_ENABLED"] == "true"
    assert environment[seed_demo.API_KEY] == credentials[seed_demo.API_KEY]
    assert environment["FRAUDSHIELD_DB_MIGRATOR_PASSWORD"] == "migrator-secret"  # noqa: S105


def test_main_passes_secrets_through_the_environment_only(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    jar = tmp_path / "tool.jar"
    jar.write_bytes(b"")
    credentials = tmp_path / ".demo-credentials"
    calls: list[tuple[list[str], dict[str, str]]] = []

    def fake_run(
        command: list[str], env: dict[str, str], check: bool
    ) -> subprocess.CompletedProcess[str]:
        calls.append((command, env))
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setenv("FRAUDSHIELD_DB_MIGRATOR_PASSWORD", "pw")
    monkeypatch.setattr(subprocess, "run", fake_run)
    argv = ["--jar", str(jar), "--credentials", str(credentials), "--db-url", "jdbc:x"]

    assert seed_demo.main(argv) == 0
    first = capsys.readouterr().out
    stored = seed_demo.parse(credentials.read_text(encoding="utf-8"))
    assert stored[seed_demo.API_KEY] in first
    assert calls[0][0] == ["java", "-jar", str(jar)]
    assert all(value not in " ".join(calls[0][0]) for value in stored.values())
    assert calls[0][1][seed_demo.API_KEY] == stored[seed_demo.API_KEY]

    assert seed_demo.main(argv) == 0
    second = capsys.readouterr().out
    assert "not printed again" in second
    assert all(value not in second for value in stored.values())


def test_main_reports_a_missing_jar(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert seed_demo.main(["--jar", str(tmp_path / "missing.jar")]) == 2
    assert "not found" in capsys.readouterr().err


def test_credentials_file_is_git_ignored() -> None:
    result = subprocess.run(  # noqa: S603 - fixed git arguments
        ["git", "-C", str(REPO_ROOT), "check-ignore", "--no-index", "-q", ".demo-credentials"],  # noqa: S607
        check=False,
    )
    assert result.returncode == 0
