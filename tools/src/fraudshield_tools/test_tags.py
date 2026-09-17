"""Discover requirement tags on tests in Python, Java and TypeScript sources.

Tags link a test to rows in docs/traceability/requirements.yaml (ADR 0004):

* Python: ``@pytest.mark.req("ID", ...)`` on a ``test_*`` function, or on a ``Test*`` class
  (applies to its test methods). Parsed with :mod:`ast`, so comments, strings and multi-line
  decorators are handled exactly. Tests marked ``@pytest.mark.skip`` are ignored.
* Java: ``@Tag("ID")`` in files under ``src/test/``. Comments and string literals are
  removed first; tags on a declaration annotated ``@Disabled``, or in a class annotated
  ``@Disabled``, are ignored.
* TypeScript: a test title starting ``[ID]`` or ``[ID, ID]`` passed to ``it``/``test``/
  ``describe`` (including ``.each(...)(title)`` tables), possibly on a following line.
  Comments are removed first; ``.skip``/``.todo`` variants and ``x``-prefixed functions are
  ignored.

Known limitation: source inspection cannot see runtime skips (``pytest.skip()`` inside a
test, ``Assumptions`` in JUnit) or a skipped enclosing ``describe``. Counting only tests that
passed in executed reports replaces this in M9, when CI publishes JUnit/JSON reports.
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass
from pathlib import Path


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
    if rel.endswith(".py"):
        return _python_tags(rel, text)
    if rel.endswith(".java"):
        return _java_tags(rel, text)
    return _typescript_tags(rel, text)


# --- Python -------------------------------------------------------------------------------


def _mark_name(decorator: ast.expr) -> str | None:
    """Return X for decorators of the form ``pytest.mark.X`` or ``pytest.mark.X(...)``."""
    node = decorator.func if isinstance(decorator, ast.Call) else decorator
    if (
        isinstance(node, ast.Attribute)
        and isinstance(node.value, ast.Attribute)
        and node.value.attr == "mark"
        and isinstance(node.value.value, ast.Name)
        and node.value.value.id == "pytest"
    ):
        return node.attr
    return None


def _req_ids(decorators: list[ast.expr]) -> tuple[list[tuple[str, int]], bool]:
    ids: list[tuple[str, int]] = []
    skipped = False
    for decorator in decorators:
        name = _mark_name(decorator)
        if name == "skip":
            skipped = True
        if name == "req" and isinstance(decorator, ast.Call):
            ids.extend(
                (arg.value, decorator.lineno)
                for arg in decorator.args
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str)
            )
    return ids, skipped


def _python_tags(rel: str, text: str) -> list[TaggedTest]:
    found: list[TaggedTest] = []
    tree = ast.parse(text, filename=rel)
    functions = (ast.FunctionDef, ast.AsyncFunctionDef)
    for node in tree.body:
        if isinstance(node, functions) and node.name.startswith("test"):
            ids, skipped = _req_ids(node.decorator_list)
            if not skipped:
                found.extend(TaggedTest(i, f"{rel}:{line}") for i, line in ids)
        elif isinstance(node, ast.ClassDef) and node.name.startswith("Test"):
            class_ids, class_skipped = _req_ids(node.decorator_list)
            for member in node.body:
                if not (isinstance(member, functions) and member.name.startswith("test")):
                    continue
                ids, skipped = _req_ids(member.decorator_list)
                if class_skipped or skipped:
                    continue
                found.extend(TaggedTest(i, f"{rel}:{line}") for i, line in [*class_ids, *ids])
    return found


# --- Java and TypeScript ------------------------------------------------------------------

_JAVA_CODE_NOISE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|//[^\n]*|/\*[\s\S]*?\*/')
_TS_CODE_NOISE = re.compile(r"//[^\n]*|/\*[\s\S]*?\*/")


def _blank(match: re.Match[str]) -> str:
    """Replace a comment or literal with spaces, keeping newlines so line numbers survive."""
    return re.sub(r"[^\n]", " ", match.group(0))


def _strip_java(text: str) -> str:
    # Keep @Tag("...") literals: blank everything else, then restore tag arguments.
    def keep_tag_literals(match: re.Match[str]) -> str:
        token = match.group(0)
        prefix = text[max(0, match.start() - 8) : match.start()]
        if (
            token.startswith('"')
            and not token.startswith('"""')
            and re.search(r"@Tag\(\s*$", prefix)
        ):
            return token
        return _blank(match)

    return _JAVA_CODE_NOISE.sub(keep_tag_literals, text)


def _line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


_JAVA_TAG = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')


def _declaration_bounds(code: str, offset: int) -> tuple[int, int]:
    """Span of the annotations and signature around ``offset``.

    Scans outwards to the nearest ``;``, ``{`` or ``}`` that is outside parentheses, so braces
    inside annotation arguments such as ``@CsvSource({...})`` do not end the span early.
    """
    depth = 0
    start = 0
    for index in range(offset - 1, -1, -1):
        char = code[index]
        if char == ")":
            depth += 1
        elif char == "(":
            depth -= 1
        elif char in ";{}" and depth <= 0:
            start = index + 1
            break
    depth = 0
    end = len(code)
    for index in range(offset, len(code)):
        char = code[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char in ";{" and depth == 0:
            end = index
            break
    return start, end


def _java_tags(rel: str, text: str) -> list[TaggedTest]:
    code = _strip_java(text)
    class_match = re.search(r"\b(class|record|interface|enum)\s+\w+", code)
    class_header = code[: class_match.start()] if class_match else ""
    if re.search(r"@Disabled\b", class_header):
        return []
    found: list[TaggedTest] = []
    for match in _JAVA_TAG.finditer(code):
        start, end = _declaration_bounds(code, match.start())
        if re.search(r"@Disabled\b", code[start:end]):
            continue
        found.append(TaggedTest(match.group(1), f"{rel}:{_line_of(code, match.start())}"))
    return found


_TS_TITLE = re.compile(
    r"""(?P<call>\b(?P<fn>x?(?:it|test|describe))(?P<mods>(?:\.\w+)*)\s*\(|\]\s*\)\s*\()"""
    r"""\s*['"`]\[(?P<ids>[A-Z0-9][A-Z0-9 ,\-]*)\]"""
)
_TS_EACH_CALL = re.compile(r"\b(?P<fn>x?(?:it|test|describe))(?P<mods>(?:\.\w+)*)\.each\s*\(")
_TS_DISABLED_MODS = {"skip", "todo"}


def _ts_disabled(fn: str, mods: str) -> bool:
    return fn.startswith("x") or bool(_TS_DISABLED_MODS & set(filter(None, mods.split("."))))


def _typescript_tags(rel: str, text: str) -> list[TaggedTest]:
    code = _TS_CODE_NOISE.sub(_blank, text)
    found: list[TaggedTest] = []
    for match in _TS_TITLE.finditer(code):
        if match.group("fn"):
            disabled = _ts_disabled(match.group("fn"), match.group("mods"))
        else:
            each_calls = list(_TS_EACH_CALL.finditer(code, 0, match.start()))
            last = each_calls[-1] if each_calls else None
            disabled = last is None or _ts_disabled(last.group("fn"), last.group("mods"))
        if disabled:
            continue
        line = f"{rel}:{_line_of(code, match.start('ids'))}"
        found.extend(TaggedTest(part.strip(), line) for part in match.group("ids").split(","))
    return found


def discover(root: Path, files: list[Path]) -> list[TaggedTest]:
    found: list[TaggedTest] = []
    for path in files:
        rel = path.relative_to(root).as_posix()
        if is_test_file(rel):
            found.extend(tags_in_file(rel, path.read_text(encoding="utf-8")))
    return found
