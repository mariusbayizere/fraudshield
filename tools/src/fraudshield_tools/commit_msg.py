"""Validate commit messages against build prompt G.3 (and G.1 rule 3).

Used as a ``commit-msg`` pre-commit hook (``fs-commit-msg <message-file>``) and in CI over the
commits a push introduces (``fs-commit-msg --rev-range BASE..HEAD``). Rules:

* Subject: ``type(scope)?: summary``, type from the G.3 list, at most 72 characters, imperative
  summary that does not end with a full stop, and not a placeholder such as "WIP" or "update".
* Second line blank when a body exists; body lines at most 72 characters except lines that are
  a single URL or indented code.
* No ``Co-Authored-By`` trailers or tool-generated signatures (G.1 rule 3).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

TYPES = (
    "feat",
    "fix",
    "perf",
    "refactor",
    "test",
    "docs",
    "build",
    "ci",
    "chore",
    "ml",
    "data",
    "sec",
)
SUBJECT = re.compile(rf"^(?:{'|'.join(TYPES)})(?:\([a-z0-9][a-z0-9-]*\))?!?: (?P<summary>\S.*)$")
MAX_LINE = 72
PLACEHOLDER_SUMMARIES = re.compile(r"^(wip|update|fix stuff|changes|misc|tmp)\b", re.IGNORECASE)
FORBIDDEN_TRAILERS = re.compile(
    r"^(co-authored-by|generated with|🤖 generated)", re.IGNORECASE | re.MULTILINE
)


def problems(message: str) -> list[str]:
    lines = [line for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return ["empty commit message"]
    found: list[str] = []
    subject = lines[0]
    match = SUBJECT.match(subject)
    if match is None:
        found.append(f"subject must be '<type>(<scope>): <summary>' with type in {TYPES}")
    elif PLACEHOLDER_SUMMARIES.match(match.group("summary")) or subject.endswith("."):
        found.append("subject summary is a placeholder or ends with a full stop")
    if len(subject) > MAX_LINE:
        found.append(f"subject is {len(subject)} characters (max {MAX_LINE})")
    if len(lines) > 1 and lines[1].strip():
        found.append("second line must be blank")
    for number, line in enumerate(lines[2:], start=3):
        exempt = re.fullmatch(r"\s*<?https?://\S+>?", line) or line.startswith("    ")
        if len(line) > MAX_LINE and not exempt:
            found.append(f"line {number} is {len(line)} characters (max {MAX_LINE})")
    if FORBIDDEN_TRAILERS.search(message):
        found.append("co-author trailers and tool signatures are not allowed (G.1)")
    return found


def _messages_in_range(rev_range: str) -> list[tuple[str, str]]:
    # Arguments are passed as a list to git (no shell); the range is a revision expression.
    shas = subprocess.run(  # noqa: S603
        ["git", "rev-list", "--reverse", "--no-merges", rev_range],  # noqa: S607
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split()
    return [
        (
            sha,
            subprocess.run(  # noqa: S603
                ["git", "log", "-1", "--format=%B", sha],  # noqa: S607
                capture_output=True,
                text=True,
                check=True,
            ).stdout,
        )
        for sha in shas
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("message_file", nargs="?", type=Path)
    group.add_argument("--rev-range", help="check every commit in a git revision range")
    args = parser.parse_args(argv)

    if args.rev_range:
        checked = _messages_in_range(args.rev_range)
    else:
        checked = [("commit message", args.message_file.read_text(encoding="utf-8"))]
    failures = 0
    for label, message in checked:
        for problem in problems(message):
            failures += 1
            print(f"ERROR {label[:12]}: {problem}", file=sys.stderr)
    print(f"commit-msg: {len(checked)} message(s) checked, {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
