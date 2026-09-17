"""Requirement tags in Vitest and Playwright titles."""

from __future__ import annotations

import re

from fraudshield_tools.test_tags import TaggedTest, blank, line_of

_COMMENT_OR_STRING = re.compile(
    r"//[^\n]*|/\*[\s\S]*?\*/|'(?:\\.|[^'\\\n])*'|\"(?:\\.|[^\"\\\n])*\"|`(?:\\.|[^`\\])*`"
)
_CALL = re.compile(r"\b(?P<fn>x?(?:it|test|describe))(?P<mods>(?:\s*\.\s*\w+)*)\s*\(")
_TITLE_PREFIX = re.compile(r"\[(?P<ids>[A-Z0-9][A-Z0-9 ,\-]*)\]")
_DISABLING_MODIFIERS = {"skip", "todo", "skipIf", "runIf", "fails"}


def _modifiers(mods: str) -> set[str]:
    return {m for m in re.split(r"\s*\.\s*", mods) if m}


def _is_disabled(fn: str, mods: str) -> bool:
    return fn.startswith("x") or bool(_DISABLING_MODIFIERS & _modifiers(mods))


def _matching_parens(code: str) -> dict[int, int]:
    """Map each ``(`` offset to its matching ``)`` offset in comment- and string-free code."""
    pairs: dict[int, int] = {}
    opened: list[int] = []
    for index, char in enumerate(code):
        if char == "(":
            opened.append(index)
        elif char == ")" and opened:
            pairs[opened.pop()] = index
    return pairs


def tags(rel: str, text: str) -> list[TaggedTest]:
    code = _COMMENT_OR_STRING.sub(lambda m: blank(m.group(0)), text)
    parens = _matching_parens(code)

    disabled_ranges: list[tuple[int, int]] = []
    title_calls: list[int] = []  # offsets of "(" whose first argument is a test title
    for call in _CALL.finditer(code):
        open_paren = call.end() - 1
        close_paren = parens.get(open_paren, len(code))
        mods = _modifiers(call.group("mods"))
        disabled = _is_disabled(call.group("fn"), call.group("mods"))
        if "each" in mods:
            # it.each(table)(title, fn): the title belongs to the call that follows the table.
            following = code.find("(", close_paren)
            if following != -1 and not code[close_paren + 1 : following].strip():
                title_calls.append(following)
                open_paren, close_paren = following, parens.get(following, len(code))
        else:
            title_calls.append(open_paren)
        if disabled:
            disabled_ranges.append((call.start(), close_paren))

    found: list[TaggedTest] = []
    for open_paren in title_calls:
        match = re.compile(r"\s*['\"`]").match(text, open_paren + 1)
        if match is None:
            continue
        title = _TITLE_PREFIX.match(text, match.end())
        if title is None:
            continue
        position = title.start("ids")
        if any(start <= open_paren <= end for start, end in disabled_ranges):
            continue
        location = f"{rel}:{line_of(text, position)}"
        found.extend(TaggedTest(part.strip(), location) for part in title.group("ids").split(","))
    return found
