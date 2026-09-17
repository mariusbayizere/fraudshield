"""Discover requirement tags on tests that will actually run.

Tags link a test to rows in docs/traceability/requirements.yaml (ADR 0004). A tag counts only
when the test it is attached to is enabled unconditionally according to the source:

* Python (:mod:`.python`): ``@pytest.mark.req("ID", ...)`` on module ``pytestmark``, a ``Test*``
  class (including nested classes) or a ``test*`` function, resolved through import aliases.
  Tests marked ``skip``, ``skipif(<literal truthy>)`` or ``xfail`` (an expected failure is not
  evidence) do not count.
* Java (:mod:`.java`): upper-case ``@Tag("ID")`` on a class or on a ``@Test``-family declaration.
  Declarations annotated with ``@Disabled`` or a conditional enablement annotation
  (``@EnabledIf…``, ``@DisabledIf…``, ``@EnabledOn…``, ``@DisabledOn…``), fully qualified or not,
  are excluded together with everything nested in them. Lower-case tags such as
  ``requires-docker`` are execution groups, not requirement IDs.
* TypeScript (:mod:`.typescript`): titles starting ``[ID]``/``[ID, ID]`` passed to
  ``it``/``test``/``describe`` or to an ``.each(...)`` table. Calls using ``skip``, ``todo``,
  ``skipIf``, ``runIf`` or an ``x`` prefix disable every title inside them, including nested
  tests. Strings other than test titles are ignored.

Remaining limitation: runtime decisions inside a test body (``pytest.skip()``, JUnit
``Assumptions``) and non-literal ``skipif`` conditions are invisible to source inspection. From
M9, CI derives tags from executed test reports and counts only passed tests.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

REQUIREMENT_TAG = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+$")


@dataclass(frozen=True)
class TaggedTest:
    requirement_id: str
    location: str


def is_test_file(rel: str) -> bool:
    name = rel.rsplit("/", 1)[-1]
    if rel.endswith(".java"):
        return "/src/test/" in rel
    if rel.endswith(".py"):
        return name.startswith("test_") or name.endswith("_test.py")
    return bool(re.search(r"\.(test|spec)\.(ts|tsx)$", name))


def tags_in_file(rel: str, text: str) -> list[TaggedTest]:
    # Imported here to keep the language modules independent of each other.
    from fraudshield_tools.test_tags import java, python, typescript  # noqa: PLC0415

    if rel.endswith(".py"):
        return python.tags(rel, text)
    if rel.endswith(".java"):
        return java.tags(rel, text)
    return typescript.tags(rel, text)


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def blank(text: str) -> str:
    """Replace every character except newlines with a space, so offsets and lines survive."""
    return re.sub(r"[^\n]", " ", text)


def discover(root: Path, files: list[Path]) -> list[TaggedTest]:
    found: list[TaggedTest] = []
    for path in files:
        rel = path.relative_to(root).as_posix()
        if is_test_file(rel):
            found.extend(tags_in_file(rel, path.read_text(encoding="utf-8")))
    return found
