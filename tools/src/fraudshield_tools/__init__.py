"""Governance tooling for the FraudShield repository."""

import subprocess
from pathlib import Path


def _find_repo_root() -> Path:
    """Locate the repository root from the working directory, falling back to this file.

    ``git rev-parse`` works for non-editable installs too; the file-relative fallback covers
    running outside a git checkout (for example from an unpacked source archive).
    """
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],  # noqa: S607
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return Path(__file__).resolve().parents[3]
    return Path(result.stdout.strip())


REPO_ROOT = _find_repo_root()
