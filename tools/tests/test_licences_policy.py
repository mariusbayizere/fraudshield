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
        ("The MIT License", "MIT"),
        ("Eclipse Distribution License - v 1.0", "BSD-3-Clause"),
        ("EDL 1.0", "BSD-3-Clause"),
        ("Permission is hereby granted, free of charge, to any person", "MIT"),
        ("Python Software Foundation License", "PSF-2.0"),
        ("3-Clause BSD License", "BSD-3-Clause"),
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
        ("SIL Open Font License 1.1", "OFL-1.1"),
        ("OFL-1.1", "OFL-1.1"),
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
        # ADR 0020: unmodified EPL-2.0 binaries may ship; other weak copyleft may not.
        ("runtime", ("EPL-2.0",), "any", True),
        ("runtime", ("EPL-1.0",), "any", False),
        ("runtime", ("LGPL-2.1-only",), "any", False),
        ("runtime", ("MPL-2.0",), "any", False),
        ("runtime", ("EPL-2.0 OR LGPL-2.1-only",), "any", True),
        ("runtime", ("EPL-2.0", "LGPL-2.1-only"), "all", False),
        ("dev", ("GNU Lesser General Public License v3 (LGPLv3)",), "any", True),
        ("dev", ("GPL-3.0-only",), "any", False),
        # Several declared licences
        ("runtime", ("MIT License", "Apache Software License"), "any", None),
        ("runtime", ("GNU General Public License v3 (GPLv3)", "MIT License"), "any", True),
        ("runtime", ("The Apache Software License, Version 2.0", "BSD"), "all", None),
        ("runtime", ("Apache License, Version 2.0", "Eclipse Public License v2.0"), "all", True),
        ("runtime", ("Apache License, Version 2.0", "Mozilla Public License 2.0"), "all", False),
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


def test_dual_licensed_runtime_exceptions_rely_on_the_epl_option() -> None:
    logback = Dependency(
        "maven",
        "ch.qos.logback:logback-core",
        "1.5.38",
        "runtime",
        ("EPL-2.0", "LGPL-2.1-only"),
        "all",
    )
    assert evaluate(logback) is True
    upgraded = Dependency(
        "maven",
        "ch.qos.logback:logback-core",
        "1.5.39",
        "runtime",
        ("EPL-2.0", "LGPL-2.1-only"),
        "all",
    )
    assert evaluate(upgraded) is False


def test_violation_messages_distinguish_unidentified_from_not_allowed() -> None:
    messages = violations([_dep("runtime", "LGPL-2.1-only"), _dep("dev", "BSD", name="vague")])
    assert messages == [
        "python:lib@1.0 (runtime): ('LGPL-2.1-only',) not allowed for runtime",
        "python:vague@1.0 (dev): unidentified licence ('BSD',)",
    ]


@pytest.mark.parametrize(
    ("ecosystem", "name", "scope", "expected"),
    [
        # ADR 0080: OFL-1.1 is allowed for font packages, in either scope.
        ("npm", "@fontsource-variable/inter", "runtime", True),
        ("npm", "@fontsource/inter", "dev", True),
        # Anywhere else it is recognised and not allowed, never unidentified.
        ("npm", "inter-font-loader", "runtime", False),
        ("npm", "@fontsource-variable/inter/../evil", "runtime", False),
        ("python", "fontsource-variable-inter", "runtime", False),
        ("maven", "org.example:fonts", "runtime", False),
    ],
)
def test_ofl_is_allowed_for_font_packages_only(
    ecosystem: str, name: str, scope: str, expected: bool
) -> None:
    dependency = Dependency(ecosystem, name, "5.3.0", scope, ("OFL-1.1",))
    assert evaluate(dependency) is expected
    # A font package still needs every other licence it declares to be allowed.
    mixed = Dependency(ecosystem, name, "5.3.0", scope, ("OFL-1.1", "GPL-3.0-only"), "all")
    assert evaluate(mixed) is False
