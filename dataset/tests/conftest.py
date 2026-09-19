"""Session-scoped guards for the dataset suite.

E15 exists because three evidence runs were broken in one session by three different causes with
one shared property: **a run's inputs changed from outside the run, and the run could not tell.**
The third came from *inside* the suite being measured — a test edited the repository's
``geography.yaml`` and restored it in a ``finally`` that did not run when the body raised — which is
what makes this a fixture rather than a discipline. No amount of care about what the operator does
outside the suite would have prevented it.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest

from .shared_state_guard import describe_changes, tree_digest

REPO_ROOT = Path(__file__).resolve().parents[2]

#: Trees whose contents a test must never modify. Every run reads these; a mutation to any of them
#: changes what a later test measures, silently and in a way that reads as an unrelated failure.
GUARDED_TREES = (REPO_ROOT / "dataset" / "generator" / "params",)


@pytest.fixture(scope="session", autouse=True)
def guard_shared_parameter_trees() -> Iterator[None]:
    """Fail the session if any guarded file changed while the suite ran (E15).

    Deliberately **not** a restore: restoring would hide the defect and re-create the ``finally``
    problem this replaces. The fixture reports and fails, because a suite that mutated its own
    inputs has already produced results that cannot be cited.
    """
    before = {root: tree_digest(root, REPO_ROOT) for root in GUARDED_TREES}
    # E12: the guard is worthless over an empty set, and a renamed or moved params directory would
    # make it one silently. Assert the precondition the guard depends on.
    assert any(before.values()), (
        f"E15 guard found no files under {[str(r) for r in GUARDED_TREES]}; the guarded tree moved "
        "and the guard is now vacuous"
    )
    yield
    changes: list[str] = []
    for root in GUARDED_TREES:
        changes += describe_changes(before[root], tree_digest(root, REPO_ROOT))
    if changes:
        listing = "\n  ".join(changes)
        pytest.fail(
            "E15: a test modified shared state outside tmp_path, so this run's results cannot be "
            "cited. A test needing a different parameter tree copies it into tmp_path and loads "
            "from there; load_parameters() takes a directory for exactly this reason."
            f"\n  {listing}",
            pytrace=False,
        )
