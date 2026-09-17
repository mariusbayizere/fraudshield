"""Requirement tags in pytest modules, parsed with :mod:`ast`."""

from __future__ import annotations

import ast
from dataclasses import dataclass, field

from fraudshield_tools.test_tags import TaggedTest

_FUNCTIONS = (ast.FunctionDef, ast.AsyncFunctionDef)


@dataclass
class _Aliases:
    """Names through which ``pytest`` and ``pytest.mark`` are reachable in a module."""

    pytest_names: set[str] = field(default_factory=lambda: {"pytest"})
    mark_names: set[str] = field(default_factory=set)
    mark_variables: dict[str, list[ast.expr]] = field(default_factory=dict)


@dataclass(frozen=True)
class _Marks:
    ids: tuple[tuple[str, int], ...] = ()
    disabled: bool = False

    def plus(self, other: _Marks) -> _Marks:
        return _Marks(self.ids + other.ids, self.disabled or other.disabled)


def _collect_aliases(tree: ast.Module) -> _Aliases:
    aliases = _Aliases()
    for node in tree.body:
        if isinstance(node, ast.Import):
            aliases.pytest_names.update(
                a.asname or a.name for a in node.names if a.name == "pytest"
            )
        elif isinstance(node, ast.ImportFrom) and node.module == "pytest":
            aliases.mark_names.update(a.asname or a.name for a in node.names if a.name == "mark")
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            target = node.targets[0]
            if isinstance(target, ast.Name) and target.id != "pytestmark":
                values = _as_list(node.value)
                if values and all(_mark_name(v, aliases) for v in values):
                    aliases.mark_variables[target.id] = values
    return aliases


def _as_list(node: ast.expr) -> list[ast.expr]:
    return list(node.elts) if isinstance(node, (ast.List, ast.Tuple)) else [node]


def _mark_name(node: ast.expr, aliases: _Aliases) -> str | None:
    """X for ``pytest.mark.X``, ``mark.X`` (from-imported) or their calls."""
    target = node.func if isinstance(node, ast.Call) else node
    if not isinstance(target, ast.Attribute):
        return None
    base = target.value
    if (
        isinstance(base, ast.Attribute)
        and base.attr == "mark"
        and isinstance(base.value, ast.Name)
        and base.value.id in aliases.pytest_names
    ):
        return target.attr
    if isinstance(base, ast.Name) and base.id in aliases.mark_names:
        return target.attr
    return None


def _is_literally_true(node: ast.expr) -> bool:
    # Only boolean/numeric literals: string conditions are evaluated by pytest at run time.
    return (
        isinstance(node, ast.Constant) and isinstance(node.value, (bool, int)) and bool(node.value)
    )


def _marks(expressions: list[ast.expr], aliases: _Aliases) -> _Marks:
    result = _Marks()
    for expression in expressions:
        if isinstance(expression, ast.Name) and expression.id in aliases.mark_variables:
            result = result.plus(_marks(aliases.mark_variables[expression.id], aliases))
            continue
        name = _mark_name(expression, aliases)
        call = expression if isinstance(expression, ast.Call) else None
        if name in {"skip", "xfail"} or (
            name == "skipif" and call is not None and call.args and _is_literally_true(call.args[0])
        ):
            result = result.plus(_Marks(disabled=True))
        elif name == "req" and call is not None:
            ids = tuple(
                (arg.value, call.lineno)
                for arg in call.args
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str)
            )
            result = result.plus(_Marks(ids=ids))
    return result


def _pytestmark(body: list[ast.stmt], aliases: _Aliases) -> _Marks:
    for node in body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "pytestmark" for t in node.targets
        ):
            return _marks(_as_list(node.value), aliases)
    return _Marks()


def _walk(
    body: list[ast.stmt], inherited: _Marks, aliases: _Aliases, rel: str, found: list[TaggedTest]
) -> None:
    for node in body:
        if isinstance(node, _FUNCTIONS) and node.name.startswith("test"):
            marks = inherited.plus(_marks(node.decorator_list, aliases))
            if not marks.disabled:
                found.extend(TaggedTest(i, f"{rel}:{line}") for i, line in marks.ids)
        elif isinstance(node, ast.ClassDef) and node.name.startswith("Test"):
            class_marks = inherited.plus(_marks(node.decorator_list, aliases))
            class_marks = class_marks.plus(_pytestmark(node.body, aliases))
            _walk(node.body, class_marks, aliases, rel, found)


def tags(rel: str, text: str) -> list[TaggedTest]:
    tree = ast.parse(text, filename=rel)
    aliases = _collect_aliases(tree)
    found: list[TaggedTest] = []
    _walk(tree.body, _pytestmark(tree.body, aliases), aliases, rel, found)
    return found
