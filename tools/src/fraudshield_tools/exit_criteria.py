"""A milestone's exit criteria, checked against evidence rather than against an author's edit.

**Why this exists.** `docs/traceability/m3_exit_criteria.md` was, for a day, the only record in
this repository asserting a milestone's completeness — and nothing checked it. Two rows said
**Met** while citing an evidence run that had not happened. Nobody wrote them dishonestly; the
table was written in the same edit as the machinery it described, and a table of intentions looks
exactly like a table of results.

Every other record-accuracy guard here watches a *generated* artefact: the parameter digest and the
dataset fingerprint for the realism report, `fs-readme-status` for the README's status line. This
one does the same for a criteria table (PB-45).

**The four kinds of evidence, and why the fourth is the load-bearing one.**

* ``test`` — a named test exists in a tracked test file.
* ``gate`` — a command that ``make governance`` runs, so CI establishes it passes.
* ``artifact`` — a file on disk that names the commit it was produced at, per the standing rule
  that a figure is quoted with its tree hash and not only its scale.
* ``judgement`` — **never auto-met.** It must name a person and say what they judged.

A checker that marked judgement criteria met would be worse than no checker: it would lend a
machine's authority to a claim no machine made, and a reader would have no way to tell which cells
were measured. So a judgement criterion may only be ``author_asserted``, and the rendered table
says so in the cell.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.provenance import parse as parse_stamp
from fraudshield_tools.repo import tracked_files

KINDS = {"test", "gate", "artifact", "judgement"}
#: A judgement criterion may hold only this status. Everything else is decided by the evidence.
AUTHOR_ASSERTED = "author_asserted"
STATUSES = {"met", "not_met", "not_started", AUTHOR_ASSERTED}

START_MARKER = "<!-- STATUS TABLE START -->"
END_MARKER = "<!-- STATUS TABLE END -->"

MAKEFILE = REPO_ROOT / "Makefile"


@dataclass(frozen=True)
class Sources:
    """Where evidence is resolved from: the repository, its test names, its wired gates.

    Bundled because they travel together and are fixed for a run — passing them individually made
    the resolver take eight parameters, which is a signature nobody reads.
    """

    root: Path
    test_names: frozenset[str]
    gates: str


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    #: Things a reader should know that are not grounds to fail a milestone. Kept separate rather
    #: than folded into errors, because a checker that fails on everything it notices gets its
    #: threshold lowered until it fails on nothing (PB-53's un-stamped artefacts are the case).
    warnings: list[str] = field(default_factory=list)
    checked: int = 0

    @property
    def ok(self) -> bool:
        return not self.errors


def load(path: Path) -> dict[str, Any]:
    loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(loaded, dict):
        raise ValueError(f"{path}: expected a mapping at the top level")
    return loaded


def test_names(root: Path) -> set[str]:
    """Every Python test function defined in a tracked test file.

    Names rather than node ids: a node id encodes a path, and moving a test between files is not a
    change to the evidence. Collected by pattern rather than by running pytest, so the check costs
    nothing and cannot be made to pass by a fixture.
    """
    found: set[str] = set()
    pattern = re.compile(r"^\s*def (test_[a-zA-Z0-9_]+)\s*\(", re.MULTILINE)
    for rel in tracked_files(root):
        name = str(rel)
        if not name.endswith(".py") or "/tests/" not in f"/{name}":
            continue
        found.update(pattern.findall((root / rel).read_text(encoding="utf-8")))
    return found


def governance_commands(root: Path) -> str:
    """The body of the Makefile's `governance` target, as text.

    A `gate` criterion is checked by confirming its command is **wired into** that target rather
    than by running it here. Running it would double the work CI already does, and — since this
    checker is itself part of `governance` — would recurse.
    """
    makefile = root / "Makefile"
    if not makefile.exists():
        # A root without one has no wired gates, which is the honest answer: a `gate` criterion
        # resolved against it will fail, and should.
        return ""
    text = makefile.read_text(encoding="utf-8")
    match = re.search(r"^governance:.*?\n((?:\t.*\n)+)", text, re.MULTILINE)
    return match.group(1) if match else ""


def _check_evidence(criterion: dict[str, Any], sources: Sources, report: Report) -> None:
    identifier = criterion.get("id", "?")
    evidence = criterion.get("evidence")
    if not isinstance(evidence, dict):
        report.errors.append(f"{identifier}: no evidence block")
        return
    kind = evidence.get("kind")
    if kind not in KINDS:
        report.errors.append(f"{identifier}: evidence kind {kind!r} is not one of {sorted(KINDS)}")
        return

    status = criterion.get("status")
    if status not in STATUSES:
        report.errors.append(f"{identifier}: status {status!r} is not one of {sorted(STATUSES)}")
        return

    if kind == "judgement":
        if status != AUTHOR_ASSERTED:
            report.errors.append(
                f"{identifier}: a judgement criterion may only be {AUTHOR_ASSERTED!r}, not "
                f"{status!r}. Marking it met would lend a machine's authority to a claim no "
                "machine made, and a reader could not tell which cells were measured"
            )
        if not str(evidence.get("asserted_by", "")).strip():
            report.errors.append(f"{identifier}: a judgement must name who asserted it")
        if not str(evidence.get("note", "")).strip():
            report.errors.append(f"{identifier}: a judgement must say what was judged")
        return

    if status == AUTHOR_ASSERTED:
        report.errors.append(
            f"{identifier}: evidence kind {kind!r} is mechanically checkable, so the status may "
            f"not be {AUTHOR_ASSERTED!r} — say met or not_met and let the evidence decide"
        )

    refs = evidence.get("ref")
    references = [refs] if isinstance(refs, str) else list(refs or [])
    if not references:
        report.errors.append(f"{identifier}: evidence names nothing")
        return

    for ref in references:
        _resolve(identifier, ref, evidence, sources, report)


def _resolve(
    identifier: str,
    ref: str,
    evidence: dict[str, Any],
    sources: Sources,
    report: Report,
) -> None:
    """Resolve one reference. Split out so each kind reads as its own rule."""
    kind = evidence["kind"]
    if kind == "test":
        if ref not in sources.test_names:
            report.errors.append(
                f"{identifier}: no test named {ref!r} exists in a tracked test file"
            )
        return
    if kind == "gate":
        if ref not in sources.gates:
            report.errors.append(
                f"{identifier}: {ref!r} is not run by the Makefile's governance target, so "
                "nothing establishes it passes"
            )
        return
    path = sources.root / ref
    if not path.exists():
        report.errors.append(f"{identifier}: artefact {ref} does not exist")
        return
    commit = str(evidence.get("commit", "")).strip()
    text = path.read_text(encoding="utf-8")
    if not commit:
        report.errors.append(
            f"{identifier}: artefact {ref} names no commit. A figure is quoted with the tree that "
            "produced it, not only with its scale"
        )
        return
    if commit not in text:
        report.errors.append(
            f"{identifier}: artefact {ref} does not name commit {commit}, so it may describe a "
            "different run than the one this row claims"
        )
        return
    _check_stamp(identifier, ref, commit, text, report)


def _check_stamp(identifier: str, ref: str, commit: str, text: str, report: Report) -> None:
    """Naming a commit is a claim; the stamp is the evidence for it (PB-53).

    An artefact can name any commit its author types. What it cannot fake is a stamp written by
    `fs-evidence` at the moment it ran, carrying the commit **and** whether the working tree was
    clean. PB-52 is the case these two answers differ on: a report naming `d85385f`, produced from
    uncommitted code that no commit contains, correct on the first check and wrong on this one.

    A missing stamp is a **warning**, not an error, and deliberately so: every artefact predating
    `fs-evidence` lacks one, and failing them would either block the milestone or invite the
    stamps to be pasted in by hand — which is the disease, not the cure. A stamp that is *present
    and contradicts the row* is an error, because that can only happen to a run made after the
    guard existed.
    """
    stamp = parse_stamp(text)
    if stamp is None:
        report.warnings.append(
            f"{identifier}: artefact {ref} carries no fs-evidence stamp, so the commit it names "
            "is the author's claim rather than the tree that ran (PB-53). Runs made before "
            "2026-09-21 have none; regenerate it through fs-evidence when it is next produced"
        )
        return
    if not stamp.clean:
        report.errors.append(
            f"{identifier}: artefact {ref} was produced on a modified working tree "
            f"(tree-state {stamp.tree_state}), so it belongs to no commit and cannot be evidence"
        )
    if not stamp.commit.startswith(commit) and not commit.startswith(stamp.commit):
        report.errors.append(
            f"{identifier}: artefact {ref} is stamped {stamp.commit[:12]} but the row cites "
            f"{commit}. The stamp is what ran"
        )


def check(register: dict[str, Any], root: Path) -> Report:
    report = Report()
    criteria = register.get("criteria")
    if not isinstance(criteria, list) or not criteria:
        report.errors.append(
            "the register lists no criteria, so this check passes over nothing (ADR 0009)"
        )
        return report

    # Discovered only when something references a test, so a register of artefacts and gates can
    # be checked against a directory that is not a git repository — which is what the tool's own
    # tests do, and what a release bundle would be.
    wants_tests = any(
        isinstance(c, dict)
        and isinstance(c.get("evidence"), dict)
        and c["evidence"].get("kind") == "test"
        for c in criteria
    )
    names = test_names(root) if wants_tests else set()
    if wants_tests and not names:
        report.errors.append(
            "no test functions were discovered, so every `test` criterion would fail for the "
            "wrong reason (ADR 0009: a check over an empty scope is untested)"
        )
        return report
    sources = Sources(root=root, test_names=frozenset(names), gates=governance_commands(root))

    seen: set[str] = set()
    for criterion in criteria:
        identifier = str(criterion.get("id", "?"))
        if identifier in seen:
            report.errors.append(f"duplicate criterion {identifier}")
        seen.add(identifier)
        if not str(criterion.get("title", "")).strip():
            report.errors.append(f"{identifier}: no title")
        _check_evidence(criterion, sources, report)
        report.checked += 1
    return report


def render_table(register: dict[str, Any]) -> str:
    """The status table, generated from the register.

    Judgement rows say **author-asserted** in the status cell and name the person, so that a reader
    scanning the table can see at a glance which rows a machine stands behind.
    """
    lines = [
        START_MARKER,
        "",
        f"<!-- Generated by `fs-exit-criteria render`. {len(register['criteria'])} criteria. -->",
        "",
        "| Criterion | Status | Evidence |",
        "|---|---|---|",
    ]
    for criterion in register["criteria"]:
        evidence = criterion["evidence"]
        kind = evidence["kind"]
        if kind == "judgement":
            status = f"**author-asserted** — {evidence['asserted_by']}"
            shown = evidence["note"]
        else:
            status = {"met": "**met**", "not_met": "**NOT MET**"}.get(
                criterion["status"], criterion["status"]
            )
            refs = evidence["ref"]
            references = [refs] if isinstance(refs, str) else list(refs)
            shown = f"{kind}: " + ", ".join(f"`{r}`" for r in references)
            if evidence.get("commit"):
                shown += f" at `{evidence['commit']}`"
        finding = criterion.get("finding")
        if finding:
            shown += f" — {finding}"
        lines.append(f"| {criterion['id']} — {criterion['title']} | {status} | {shown} |")
    lines += ["", END_MARKER]
    return "\n".join(lines)


def apply_table(document: Path, table: str) -> bool:
    """Replace the generated block in `document`. Returns True when the file changed."""
    text = document.read_text(encoding="utf-8")
    start, end = text.find(START_MARKER), text.find(END_MARKER)
    if start == -1 or end == -1:
        raise ValueError(
            f"{document} has no generated-table markers; add {START_MARKER} and {END_MARKER}"
        )
    updated = text[:start] + table + text[end + len(END_MARKER) :]
    if updated == text:
        return False
    document.write_text(updated, encoding="utf-8")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-exit-criteria", description=__doc__)
    parser.add_argument("--register", type=Path, default=None)
    parser.add_argument("--document", type=Path, default=None)
    parser.add_argument("--render", action="store_true", help="rewrite the table in the document")
    parser.add_argument(
        "--run-gates", action="store_true", help="also execute each gate command (CI does this)"
    )
    args = parser.parse_args(argv)

    register_path = args.register or REPO_ROOT / "docs" / "traceability" / "m3_exit_criteria.yaml"
    document_path = args.document or REPO_ROOT / "docs" / "traceability" / "m3_exit_criteria.md"
    register = load(register_path)
    report = check(register, REPO_ROOT)

    if args.run_gates and report.ok:
        for criterion in register["criteria"]:
            evidence = criterion["evidence"]
            if evidence["kind"] != "gate":
                continue
            refs = evidence["ref"]
            for ref in [refs] if isinstance(refs, str) else refs:
                finished = subprocess.run(ref, shell=True, cwd=REPO_ROOT, check=False)  # noqa: S602
                if finished.returncode != 0:
                    report.errors.append(f"{criterion['id']}: gate {ref!r} exited non-zero")

    table = render_table(register)
    if args.render:
        changed = apply_table(document_path, table)
        print(f"{'wrote' if changed else 'unchanged'} {document_path}")
    elif report.ok:
        current = document_path.read_text(encoding="utf-8")
        start, end = current.find(START_MARKER), current.find(END_MARKER)
        if start == -1 or table != current[start : end + len(END_MARKER)]:
            report.errors.append(
                f"{document_path.name} is stale; run: uv run fs-exit-criteria --render"
            )

    for error in report.errors:
        print(f"ERROR {error}", file=sys.stderr)
    for warning in report.warnings:
        print(f"WARN  {warning}", file=sys.stderr)
    milestone = register.get("milestone", "?")
    print(
        f"exit-criteria: {milestone}, {report.checked} criteria checked, "
        f"{len(report.errors)} errors, {len(report.warnings)} warnings"
    )
    return 0 if report.ok else 1


if __name__ == "__main__":  # pragma: no cover - module entry point
    raise SystemExit(main())
