"""Boundary cases of the dependency licence policy (ADR 0009, re-review finding R-4)."""

from __future__ import annotations

import pytest

from fraudshield_tools.licences import Dependency, evaluate, normalise, violations


def _dep(scope: str, *declared: str, combine: str = "any", name: str = "lib") -> Dependency:
    return Dependency("python", name, "1.0", scope, declared, combine)


@pytest.mark.parametrize(
    ("raw", "spdx"),
    [
        ("The Apache Software License, Version 2.0", "Apache-2.0"),
        ("Eclipse Public License v2.0", "EPL-2.0"),
        ("Eclipse Public License - v 2.0", "EPL-2.0"),
        ("MIT License", "MIT"),
        ("Permission is hereby granted, free of charge, to any person", "MIT"),
        ("Python Software Foundation License", "PSF-2.0"),
        ("Mozilla Public License 2.0 (MPL 2.0)", "MPL-2.0"),
        ("GNU Lesser General Public License v2 or later (LGPLv2+)", "LGPL-2.1-or-later"),
        ("GNU Lesser General Public License v3 (LGPLv3)", "LGPL-3.0-only"),
        ("GNU General Public License v3 (GPLv3)", "GPL-3.0-only"),
        ("GNU Affero General Public License v3", "AGPL-3.0-only"),
        ("GPL-2.0+", "GPL-2.0-or-later"),
        ("Server Side Public License", "SSPL-1.0"),
        # Ambiguous or embedded forms are not guessed.
        ("BSD", None),
        ("BSD License", None),
        ("This is not MIT licensed", None),
        ("UNLICENSED", None),
        ("SEE LICENSE IN LICENSE.md", None),
    ],
)
def test_normalise(raw: str, spdx: str | None) -> None:
    assert normalise(raw) == spdx


@pytest.mark.parametrize(
    ("scope", "declared", "combine", "expected"),
    [
        # SPDX expressions
        ("runtime", ("Apache-2.0 OR BSD-2-Clause",), "any", True),
        ("runtime", ("MIT AND Zlib",), "any", True),
        ("runtime", ("MIT AND GPL-3.0-only",), "any", False),
        ("runtime", ("(MIT OR GPL-3.0-only) AND BSD-3-Clause",), "any", True),
        ("runtime", ("GPL-2.0-only WITH Classpath-exception-2.0",), "any", False),
        ("runtime", ("Apache-2.0 WITH LLVM-exception",), "any", True),
        ("runtime", ("MIT AND Foo-1.0",), "any", None),
        ("runtime", ("(MIT",), "any", None),
        # Scope boundary: weak copyleft only for dev
        ("dev", ("EPL-2.0",), "any", True),
        ("runtime", ("EPL-2.0",), "any", False),
        ("dev", ("GNU Lesser General Public License v3 (LGPLv3)",), "any", True),
        ("dev", ("GPL-3.0-only",), "any", False),
        # Several declared licences
        ("runtime", ("MIT License", "Apache Software License"), "any", None),
        ("runtime", ("GNU General Public License v3 (GPLv3)", "MIT License"), "any", True),
        ("runtime", ("The Apache Software License, Version 2.0", "BSD"), "all", None),
        ("runtime", ("Apache License, Version 2.0", "Eclipse Public License v2.0"), "all", False),
        ("dev", ("Apache License, Version 2.0", "Eclipse Public License v2.0"), "all", True),
        # Nothing declared
        ("dev", (), "any", None),
    ],
)
def test_evaluate(
    scope: str, declared: tuple[str, ...], combine: str, expected: bool | None
) -> None:
    assert evaluate(_dep(scope, *declared, combine=combine)) is expected


def test_classifier_lists_are_dual_licences_but_unknown_members_block() -> None:
    # "any": one allowed option suffices only if every option was identified, so an unreadable
    # classifier cannot hide behind a permissive one.
    assert evaluate(_dep("runtime", "MIT License", "Weird Custom License")) is None


def test_exceptions_are_version_pinned() -> None:
    archunit = Dependency(
        "maven",
        "com.tngtech.archunit:archunit",
        "1.5.0",
        "dev",
        ("The Apache Software License, Version 2.0", "BSD"),
        "all",
    )
    upgraded = Dependency(
        "maven",
        "com.tngtech.archunit:archunit",
        "1.6.0",
        "dev",
        ("The Apache Software License, Version 2.0", "BSD"),
        "all",
    )
    assert evaluate(archunit) is True
    assert evaluate(upgraded) is None


def test_violation_messages_distinguish_unidentified_from_not_allowed() -> None:
    messages = violations([_dep("runtime", "EPL-2.0"), _dep("dev", "BSD", name="vague")])
    assert messages == [
        "python:lib@1.0 (runtime): ('EPL-2.0',) not allowed for runtime",
        "python:vague@1.0 (dev): unidentified licence ('BSD',)",
    ]
