"""Requirement tags in JUnit 5 sources.

The source is reduced to code (comments and string literals blanked, except ``@Tag`` arguments)
and scanned once, tracking brace scopes. Each declaration is the text between the previous
``;``/``{``/``}`` at parenthesis depth 0 and the ``{`` or ``;`` that ends its header.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from fraudshield_tools.test_tags import REQUIREMENT_TAG, TaggedTest, blank, line_of

_NOISE = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*[\s\S]*?\*/'
)
_ANNOTATION_PREFIX = r"@(?:[\w$]+\.)*"
_TAG = re.compile(_ANNOTATION_PREFIX + r'Tag\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)')
_TEST_ANNOTATION = re.compile(
    _ANNOTATION_PREFIX
    + r"(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\b"
)
_NOT_UNCONDITIONAL = re.compile(
    _ANNOTATION_PREFIX
    + r"(?:Disabled\w*|EnabledIf\w*|EnabledOn\w*|EnabledFor\w*|EnabledInNativeImage)\b"
)
_TYPE_KEYWORD = re.compile(r"\b(?:class|interface|enum|record)\s+[\w$]+")


def _strip(text: str) -> str:
    def keep_tag_arguments(match: re.Match[str]) -> str:
        token = match.group(0)
        before = text[max(0, match.start() - 40) : match.start()]
        if (
            token.startswith('"')
            and not token.startswith('"""')
            and re.search(r"Tag\(\s*(?:value\s*=\s*)?$", before)
        ):
            return token
        return blank(token)

    return _NOISE.sub(keep_tag_arguments, text)


@dataclass
class _Scope:
    kind: str  # "type" or "body"
    disabled: bool
    tags: list[tuple[str, int]] = field(default_factory=list)
    enabled_tests: int = 0
    parent: _Scope | None = None


def _requirement_tags(header: str, code: str, header_start: int) -> list[tuple[str, int]]:
    return [
        (m.group(1), header_start + m.start())
        for m in _TAG.finditer(header)
        if REQUIREMENT_TAG.match(m.group(1))
    ]


def tags(rel: str, text: str) -> list[TaggedTest]:
    code = _strip(text)
    found: list[TaggedTest] = []
    root = _Scope(kind="type", disabled=False)
    stack: list[_Scope] = [root]
    types: list[_Scope] = []
    paren_depth = 0
    segment_start = 0

    def declaration(end: int, opens_scope: bool) -> None:
        nonlocal segment_start
        header = code[segment_start:end]
        scope = stack[-1]
        if scope.kind != "type":
            if opens_scope:
                stack.append(_Scope(kind="body", disabled=True, parent=scope))
            return
        disabled = scope.disabled or bool(_NOT_UNCONDITIONAL.search(header))
        requirement_tags = _requirement_tags(header, code, segment_start)
        if _TYPE_KEYWORD.search(header) and opens_scope:
            child = _Scope(kind="type", disabled=disabled, tags=requirement_tags, parent=scope)
            types.append(child)
            stack.append(child)
            return
        if _TEST_ANNOTATION.search(header) and not disabled:
            for id_, offset in requirement_tags:
                found.append(TaggedTest(id_, f"{rel}:{line_of(code, offset)}"))
            ancestor: _Scope | None = scope
            while ancestor is not None:
                ancestor.enabled_tests += 1
                ancestor = ancestor.parent
        if opens_scope:
            stack.append(_Scope(kind="body", disabled=True, parent=scope))

    for index, char in enumerate(code):
        if char == "(":
            paren_depth += 1
        elif char == ")":
            paren_depth -= 1
        elif paren_depth == 0 and char == "{":
            declaration(index, opens_scope=True)
            segment_start = index + 1
        elif paren_depth == 0 and char == ";":
            declaration(index, opens_scope=False)
            segment_start = index + 1
        elif paren_depth == 0 and char == "}":
            if len(stack) > 1:
                stack.pop()
            segment_start = index + 1

    for scope in types:
        if not scope.disabled and scope.enabled_tests > 0:
            found.extend(
                TaggedTest(i, f"{rel}:{line_of(code, offset)}") for i, offset in scope.tags
            )
    return sorted(found, key=lambda t: (int(t.location.rsplit(":", 1)[1]), t.requirement_id))
