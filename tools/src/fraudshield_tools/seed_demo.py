"""Seed the local database with demo accounts and locally generated credentials (ADR 0019).

The repository is public, so no demo password or API key is committed. On first use this command
generates a random password per demo account, a development API key and an API-key pepper, writes
them to the git-ignored ``.demo-credentials`` file (mode 600) and prints them once. Later runs reuse
the file without printing. It then starts the database tool (``backend/persistence``) with the
``demo`` profile, which applies the migrations and seeds the demo institution idempotently.

The credentials reach the Java process through its environment, never its command line, so they do
not appear in process listings. The database tool itself refuses to seed under any profile other
than ``dev`` or ``demo``.

Usage: ``make seed-demo`` (Compose stack), or for another database
``uv run --package fraudshield-tools fs-seed-demo --db-url jdbc:postgresql://...``.
"""

from __future__ import annotations

import argparse
import os
import secrets
import string
import subprocess
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

from fraudshield_tools import REPO_ROOT

CREDENTIALS_FILE = REPO_ROOT / ".demo-credentials"
ENV_FILE = REPO_ROOT / ".env"
JAR = REPO_ROOT / "backend/persistence/target/fraudshield-persistence-0.1.0-SNAPSHOT.jar"
DEFAULT_DB_URL = "jdbc:postgresql://127.0.0.1:5432/fraudshield_db"

# Demo accounts in the order printed; the keys are the Spring relaxed-binding environment names of
# fraudshield.demo-seed.passwords.* (DemoSeedProperties.Passwords).
_PASSWORDS = "FRAUDSHIELD_DEMOSEED_PASSWORDS_"
ACCOUNTS: dict[str, str] = {
    f"{_PASSWORDS}ANALYST": "analyst.demo@example.com (ANALYST)",
    f"{_PASSWORDS}SENIORANALYST": "senior.analyst.demo@example.com (SENIOR_ANALYST)",
    f"{_PASSWORDS}RISKOFFICERA": "risk.officer.a.demo@example.com (RISK_OFFICER)",
    f"{_PASSWORDS}RISKOFFICERB": "risk.officer.b.demo@example.com (RISK_OFFICER)",
    f"{_PASSWORDS}ADMIN": "admin.demo@example.com (ADMIN)",
}
API_KEY = "FRAUDSHIELD_DEMOSEED_APIKEY"
PEPPER = "FRAUDSHIELD_DEMOSEED_APIKEYPEPPERHEX"
REQUIRED = (*ACCOUNTS, API_KEY, PEPPER)

PASSWORD_LENGTH = 24
# Specials without shell, quoting or escaping meaning, so the file stays simple KEY=VALUE text.
SPECIALS = "!#%+-.:=?@^_~"
PASSWORD_ALPHABET = string.ascii_letters + string.digits + SPECIALS
KEY_ID_ALPHABET = string.ascii_lowercase + string.digits


class SeedDemoError(RuntimeError):
    """The demo seed cannot run with the given files or settings."""


def generate_password(length: int = PASSWORD_LENGTH) -> str:
    """A random password meeting the password policy (FR-07-07): every character class present."""
    if length < 12:
        raise ValueError("demo passwords are at least 12 characters")
    while True:
        candidate = "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(length))
        if (
            any(c in string.ascii_uppercase for c in candidate)
            and any(c in string.ascii_lowercase for c in candidate)
            and any(c in string.digits for c in candidate)
            and any(c in SPECIALS for c in candidate)
        ):
            return candidate


def generate_api_key() -> str:
    """A development API key, ``fsk_dev_<12 [a-z0-9]>_<43 base64url>`` (ADR 0011)."""
    key_id = "".join(secrets.choice(KEY_ID_ALPHABET) for _ in range(12))
    return f"fsk_dev_{key_id}_{secrets.token_urlsafe(32)}"


def generate() -> dict[str, str]:
    credentials = {name: generate_password() for name in ACCOUNTS}
    credentials[API_KEY] = generate_api_key()
    credentials[PEPPER] = secrets.token_hex(32)
    return credentials


def render(credentials: Mapping[str, str]) -> str:
    lines = [
        "# FraudShield demo credentials, generated locally by `make seed-demo` (ADR 0019).",
        "# SYNTHETIC DATA ONLY. Git-ignored; never commit or share. Delete to regenerate",
        "# (then reset the database, or the stored hashes no longer match).",
    ]
    lines += [f"{name}={credentials[name]}" for name in REQUIRED]
    return "\n".join(lines) + "\n"


def parse(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            name, separator, value = line.partition("=")
            if not separator:
                raise SeedDemoError(f"malformed line in credentials file: {name.strip()!r}")
            values[name.strip()] = value.strip()
    return values


def write_new(path: Path, credentials: Mapping[str, str]) -> None:
    """Create the file with mode 600; never replaces an existing file."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(render(credentials))


def load_or_create(path: Path) -> tuple[dict[str, str], bool]:
    """The credentials and whether they were created by this call."""
    if path.exists():
        if path.stat().st_mode & 0o077:
            raise SeedDemoError(
                f"{path.name} is readable by other users; run chmod 600 {path.name}"
            )
        credentials = parse(path.read_text(encoding="utf-8"))
        missing = [name for name in REQUIRED if not credentials.get(name)]
        if missing:
            raise SeedDemoError(
                f"{path.name} is missing {', '.join(missing)}; delete it to regenerate"
            )
        return credentials, False
    credentials = generate()
    write_new(path, credentials)
    return credentials, True


def announcement(credentials: Mapping[str, str], path: Path) -> str:
    lines = [
        "",
        "SYNTHETIC DATA — NOT FOR PRODUCTION. Demo credentials (shown once, stored in "
        f"{path.name}, mode 600):",
    ]
    lines += [f"  {label:52} {credentials[name]}" for name, label in ACCOUNTS.items()]
    lines += [f"  {'API key (demo-bank)':52} {credentials[API_KEY]}", ""]
    return "\n".join(lines)


def migrator_password(environ: Mapping[str, str], env_file: Path) -> str:
    if environ.get("FRAUDSHIELD_DB_MIGRATOR_PASSWORD"):
        return environ["FRAUDSHIELD_DB_MIGRATOR_PASSWORD"]
    if env_file.exists():
        password = parse(env_file.read_text(encoding="utf-8")).get("FS_MIGRATOR_DB_PASSWORD")
        if password:
            return password
    raise SeedDemoError(
        "no fs_migrator password: set FRAUDSHIELD_DB_MIGRATOR_PASSWORD or run `make env`"
    )


def child_environment(
    base: Mapping[str, str], credentials: Mapping[str, str], db_url: str, password: str
) -> dict[str, str]:
    environment = {
        name: value for name, value in base.items() if not name.startswith("FRAUDSHIELD_")
    }
    environment.update({name: credentials[name] for name in REQUIRED})
    environment.update(
        {
            "FRAUDSHIELD_DB_URL": db_url,
            "FRAUDSHIELD_DB_MIGRATOR_USER": "fs_migrator",
            "FRAUDSHIELD_DB_MIGRATOR_PASSWORD": password,
            "FRAUDSHIELD_DEMOSEED_ENABLED": "true",
            "SPRING_PROFILES_ACTIVE": "demo",
        }
    )
    return environment


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("--db-url", default=os.environ.get("FRAUDSHIELD_DB_URL", DEFAULT_DB_URL))
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--credentials", type=Path, default=CREDENTIALS_FILE)
    parser.add_argument("--java", default="java")
    args = parser.parse_args(argv)
    try:
        if not args.jar.is_file():
            raise SeedDemoError(f"{args.jar} not found; build it with `make seed-demo`")
        password = migrator_password(os.environ, ENV_FILE)
        credentials, created = load_or_create(args.credentials)
    except SeedDemoError as error:
        print(f"seed-demo: {error}", file=sys.stderr)
        return 2
    if created:
        print(announcement(credentials, args.credentials), flush=True)
    else:
        print(
            f"seed-demo: reusing the credentials in {args.credentials.name} (not printed again)",
            flush=True,
        )
    completed = subprocess.run(  # noqa: S603 - fixed program and arguments; secrets only in env
        [args.java, "-jar", str(args.jar)],
        env=child_environment(os.environ, credentials, args.db_url, password),
        check=False,
    )
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
