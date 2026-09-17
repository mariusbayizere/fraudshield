"""Repository file listing shared by the governance checks."""

from __future__ import annotations

import subprocess
from pathlib import Path


def tracked_files(root: Path) -> list[Path]:
    """Files tracked or staged in git plus untracked, non-ignored files."""
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],  # noqa: S607
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return [root / line for line in result.stdout.splitlines() if (root / line).is_file()]
