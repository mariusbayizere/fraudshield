"""What tree an evidence run was actually made on (PB-53).

**The failure this exists to prevent, stated concretely.** On 2026-09-19 a realism report was
generated, committed, and labelled with a commit hash. The hash was wrong — not mistyped, but
*believed*: the run was made on a working tree carrying uncommitted changes, and the commit named
identifies a tree whose generator produces a different dataset. Six documents then explained the
discrepancy with a mechanism that measurement later refuted, and the code that produced the
artefact is gone. Two days passed and nothing detected it. That is PB-52.

**Why naming a commit cannot be the guard.** A commit hash identifies a tree in the object
database. The interpreter imports the *working* tree. The two coincide only when the working tree
is clean, and a hash written by hand cannot tell you whether it was. So the stamp carries two
values and neither substitutes for the other:

* ``commit`` — which tree the author believes they are on;
* ``tree_state`` — a hash of ``git status --porcelain``, which is :data:`CLEAN` for an unmodified
  tree and varies with any modification, staged or not, tracked or not.

A stamp reading ``clean`` makes the commit a claim about the code that ran. A stamp reading
anything else says the artefact is not attributable to any commit, which is the true state of the
1,012,522-row report and was invisible for want of one line.

**Untracked files count as dirty, deliberately.** The archetypal accident is a new module that is
imported and not yet added; ignoring untracked files would make exactly that case read clean.
"""

from __future__ import annotations

import hashlib
import subprocess
from dataclasses import dataclass
from pathlib import Path

#: The tree_state of a tree with no modifications. A literal rather than a hash of the empty
#: string, so a reader of an artefact header can recognise it without computing anything.
CLEAN = "clean"

#: Header lines a stamped artefact carries. Parsed back by `parse`, so the format is a contract.
COMMIT_FIELD = "# evidence-commit:"
STATE_FIELD = "# evidence-tree-state:"


class DirtyTreeError(RuntimeError):
    """An evidence run was attempted on a tree that is not attributable to any commit."""


@dataclass(frozen=True)
class Stamp:
    """Where an artefact came from, as two facts that are cheap to check and hard to fake."""

    commit: str
    tree_state: str

    @property
    def clean(self) -> bool:
        return self.tree_state == CLEAN

    def header(self) -> str:
        """The lines to write at the top of an artefact, before anything it measures."""
        lines = [f"{COMMIT_FIELD} {self.commit}", f"{STATE_FIELD} {self.tree_state}"]
        if not self.clean:
            lines.append(
                "# NOT CITABLE: produced on a modified working tree, so the commit above names a "
                "tree that did not run. See PB-52 for what this costs."
            )
        return "\n".join(lines) + "\n"


def _git(root: Path, *args: str) -> str:
    result = subprocess.run(  # noqa: S603 - fixed git subcommands, no user input reaches argv
        ["git", *args],  # noqa: S607
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout


def tree_state(root: Path) -> str:
    """`CLEAN`, or a digest of the porcelain status.

    The digest is of the status text rather than of the diff, because it must also change when a
    file is added or removed — a diff of tracked content would not see an untracked new module,
    which is the case that motivates this.
    """
    status = _git(root, "status", "--porcelain")
    if not status.strip():
        return CLEAN
    return hashlib.sha256(status.encode("utf-8")).hexdigest()[:16]


def stamp(root: Path) -> Stamp:
    return Stamp(commit=_git(root, "rev-parse", "HEAD").strip(), tree_state=tree_state(root))


def require_clean(root: Path, *, allow_dirty: bool = False) -> Stamp:
    """The stamp, refusing on a dirty tree unless the caller has said otherwise in so many words.

    The escape exists because a development run on a modified tree is a normal and useful thing to
    do. What it must not do is produce an artefact indistinguishable from a citable one — so
    ``allow_dirty`` does not suppress the record, it stamps it.
    """
    current = stamp(root)
    if current.clean or allow_dirty:
        return current
    raise DirtyTreeError(
        "this tree has uncommitted changes, so any artefact produced now belongs to no commit. "
        "Commit or stash them, or pass --allow-dirty to produce a run marked NOT CITABLE. "
        "PB-52 is what happens when neither is done."
    )


def parse(text: str) -> Stamp | None:
    """The stamp an artefact carries, or None if it carries none."""
    commit = state = ""
    for line in text.splitlines():
        if line.startswith(COMMIT_FIELD):
            commit = line[len(COMMIT_FIELD) :].strip()
        elif line.startswith(STATE_FIELD):
            state = line[len(STATE_FIELD) :].strip()
    if not commit or not state:
        return None
    return Stamp(commit=commit, tree_state=state)
