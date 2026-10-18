"""The README's status line must agree with the milestone register.

The README is the most public file in the repository, and for two whole milestones it said
"Project status: M0 — bootstrap and governance. No fraud model, dataset or service is implemented
yet" while M1 was merged and M2 held a verified dataset generator. Nobody noticed, because nothing
looked: every other record-accuracy guard in this project watches a generated artefact, and the
README is written by hand (M2 milestone review).

The check is deliberately narrow. It does not judge the prose, only that the milestone named in the
status line is the one the register says the project is working on.
"""

from __future__ import annotations

import re
import sys

import yaml

from fraudshield_tools import REPO_ROOT

README = REPO_ROOT / "README.md"
MILESTONES = REPO_ROOT / "docs" / "traceability" / "milestones.yaml"
# > **Project status: M2 — the synthetic dataset generator.** ...
STATUS = re.compile(r"^>\s*\*\*Project status:\s*(M\d+)\b", re.MULTILINE)


def current_milestone() -> str:
    register = yaml.safe_load(MILESTONES.read_text(encoding="utf-8"))
    current = register.get("current")
    if not isinstance(current, str) or not re.fullmatch(r"M\d+", current):
        raise ValueError(
            f"{MILESTONES.name}: 'current' must be a milestone like M2, got {current!r}"
        )
    return current


def readme_milestone() -> str:
    found = STATUS.search(README.read_text(encoding="utf-8"))
    if found is None:
        raise ValueError(
            "README.md has no status line; expected a line beginning "
            '"> **Project status: M<n> — ..."'
        )
    return found.group(1)


def main(argv: list[str] | None = None) -> int:
    try:
        expected, stated = current_milestone(), readme_milestone()
    except ValueError as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    if stated != expected:
        print(
            f"ERROR README.md says the project is at {stated}, but "
            f"docs/traceability/milestones.yaml says {expected}. Update the README status line.",
            file=sys.stderr,
        )
        return 1
    print(f"README status line agrees with the register ({expected})")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
