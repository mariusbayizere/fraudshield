"""Dependency licence inventory and policy check (build prompt F.3; ADR 0009).

Collects every third-party dependency of the three ecosystems with its scope:

* ``runtime`` — shipped in a service image: Maven compile/runtime scope, npm ``dependencies``,
  and the non-dev dependencies of the ``fraudshield-ml`` package.
* ``dev`` — build, test and developer tooling only, never distributed.

and fails when a licence is not allowed for that scope, or cannot be identified. Build-time
Maven *plugins* (Checkstyle, SpotBugs, FindSecBugs, Spotless) are not dependencies of any
artifact and are assessed in ADR 0009 rather than here.
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

from fraudshield_tools import REPO_ROOT

PERMISSIVE = frozenset(
    {
        "Apache-2.0",
        "MIT",
        "MIT-0",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "ISC",
        "0BSD",
        "PSF-2.0",
        "Python-2.0",
        "CC0-1.0",
        "BlueOak-1.0.0",
        "Zlib",
        "Unlicense",
    }
)
# Weak-copyleft and documentation licences acceptable for tools that are never distributed.
DEV_ONLY = frozenset(
    {
        "EPL-1.0",
        "EPL-2.0",
        "MPL-2.0",
        "LGPL-2.1-only",
        "LGPL-2.1-or-later",
        "LGPL-3.0-only",
        "LGPL-3.0-or-later",
        "CC-BY-4.0",
    }
)
# Recognised so that they fail as "not allowed" rather than "unidentified".
DENIED = frozenset(
    {
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-3.0-only",
        "GPL-3.0-or-later",
        "AGPL-3.0-only",
        "AGPL-3.0-or-later",
        "SSPL-1.0",
        "BUSL-1.1",
        "Elastic-2.0",
    }
)
KNOWN = PERMISSIVE | DEV_ONLY | DENIED
# Weak copyleft allowed at runtime for unmodified third-party binaries only (ADR 0020): the ASF
# "Category B" position. Spring Boot's logging and Jakarta APIs are EPL-2.0 (or dual-licensed).
RUNTIME_WEAK_COPYLEFT = frozenset({"EPL-2.0"})
ALLOWED = {"runtime": PERMISSIVE | RUNTIME_WEAK_COPYLEFT, "dev": PERMISSIVE | DEV_ONLY}
# SPDX exceptions that do not change the base licence's acceptability.
ALLOWED_WITH_EXCEPTIONS = frozenset({"LLVM-exception"})

# Reviewed, version-pinned decisions where metadata is missing or ambiguous; an upgrade of the
# package removes the exception and forces a new review. Value: (SPDX expression, reason).
EXCEPTIONS: dict[str, tuple[str, str]] = {
    "python:pg8000@1.31.5": (
        "BSD-3-Clause",
        "classifier says only 'BSD License'; dist-info/licenses/LICENSE is headed 'BSD 3-Clause "
        "License' (verified 2026-09-23). The scorer's PostgreSQL driver for the feature store's "
        "database fallback (ADR 0062 point 6)",
    ),
    "python:scramp@1.4.17": (
        "MIT-0",
        "the classifier names 'MIT No Attribution License (MIT-0)' in words the matcher does not "
        "map; the LICENSE file is the MIT No Attribution text (verified 2026-09-23). pg8000's "
        "SCRAM authentication",
    ),
    "python:python-dateutil@2.9.0.post0": (
        "Apache-2.0 AND BSD-3-Clause",
        "metadata says 'Dual License' with both classifiers; the LICENSE file states that the "
        "BSD 3-Clause licence applies to all code, even that also covered by Apache 2.0, so both "
        "apply and both are permissive (verified 2026-09-23). pg8000's dependency",
    ),
    "python:nodeenv@1.10.0": (
        "BSD-3-Clause",
        "metadata says only 'BSD'; dist-info/licenses/LICENSE is the 3-clause text with the "
        "non-endorsement clause (verified 2026-09-17)",
    ),
    "python:jsonschema-path@0.5.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; dist-info/licenses/LICENSE is the "
        "Apache License, Version 2.0 text (verified 2026-09-17)",
    ),
    "python:h3@4.5.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License', which is ambiguous between 1.0, 1.1 and "
        "2.0, and there is no License-Expression. The METADATA License field carries the full "
        "licence text headed 'Apache License, Version 2.0', and "
        "dist-info/licenses/LICENSE is the same text (verified 2026-09-19). First runtime "
        "dependency of fraudshield-ml; see ADR 0009's 2026-09-19 amendment",
    ),
    "python:pathable@0.6.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; dist-info/licenses/LICENSE is the "
        "Apache License, Version 2.0 text (verified 2026-09-17)",
    ),
    "python:openapi-schema-validator@0.9.0": (
        "BSD-3-Clause",
        "classifier says only 'BSD License'; dist-info/licenses/LICENSE is headed "
        "'BSD 3-Clause License' with the non-endorsement clause (verified 2026-09-17)",
    ),
    "maven:ch.qos.logback:logback-classic@1.5.38": (
        "EPL-2.0 OR LGPL-2.1-only",
        "POM lists EPL-2.0 and LGPL-2.1-only as separate entries; LICENSE.txt at tag v_1.5.38 says "
        "dual-licensed 'per the licensee's choosing' (verified 2026-09-17)",
    ),
    "maven:ch.qos.logback:logback-core@1.5.38": (
        "EPL-2.0 OR LGPL-2.1-only",
        "same project and licence file as logback-classic 1.5.38 (verified 2026-09-17)",
    ),
    "maven:jakarta.annotation:jakarta.annotation-api@3.0.0": (
        "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
        "POM lists 'EPL 2.0' and 'GPL2 w/ CPE'; the jar's META-INF/NOTICE.md declares "
        "SPDX 'EPL-2.0 OR GPL-2.0-only with Classpath-exception-2.0' (verified 2026-09-17)",
    ),
    "maven:jakarta.persistence:jakarta.persistence-api@3.2.0": (
        "BSD-3-Clause",
        "Offered as 'EPL-2.0 OR BSD-3-Clause': the POM lists 'Eclipse Distribution License v. 1.0' "
        "and 'Eclipse Public License v. 2.0', and the jar's META-INF/NOTICE.md declares that SPDX "
        "expression (EDL 1.0 is the BSD 3-Clause text). The project ELECTS BSD-3-Clause, the "
        "permissive option (owner decision 2026-09-22). Runtime API of the JPA persistence layer "
        "(ADR 0071, and ADR 0068 for M6); ADR 0020 (verified 2026-09-22)",
    ),
    "maven:jakarta.transaction:jakarta.transaction-api@2.0.1": (
        "EPL-2.0",
        "Offered as 'EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0': the POM lists 'EPL 2.0' and "
        "'GPL2 w/ CPE', and the jar's META-INF/NOTICE.md declares that SPDX expression. The "
        "project ELECTS EPL-2.0, used unmodified as a runtime dependency (ADR 0020 weak-copyleft "
        "rule; owner decision 2026-09-22). Required by hibernate-core for the JPA persistence "
        "layer (ADR 0071, and ADR 0068 for M6) (verified 2026-09-22)",
    ),
    "maven:com.tngtech.archunit:archunit@1.5.0": (
        "Apache-2.0 AND BSD-3-Clause",
        "POM declares Apache-2.0 and 'BSD'; the BSD part is shaded ASM, whose bundled "
        "asm.license is the 3-clause text (verified 2026-09-17)",
    ),
    "python:xgboost@3.0.2": (
        "Apache-2.0",
        "classifier says only 'Apache Software License', ambiguous between versions and there is "
        "no License-Expression; no LICENSE file ships in the wheel's dist-info. Confirmed against "
        "the tagged source (github.com/dmlc/xgboost, tag v3.0.2, LICENSE) on 2026-09-22: the "
        "Apache License, Version 2.0 (C-6, first runtime dependency of the ml/pyproject.toml "
        "ensemble baseline)",
    ),
    "python:scipy@1.18.1": (
        "BSD-3-Clause",
        "classifier says only 'BSD License'; dist-info/licenses/LICENSE.txt is headed 'Copyright "
        "(c) 2001-2002 Enthought, Inc. 2003, SciPy Developers' with the 3-clause disclaimer, "
        "confirmed against the tagged source (github.com/scipy/scipy, tag v1.18.1) on 2026-09-22. "
        "A transitive dependency of xgboost and lightgbm, not declared directly",
    ),
    "python:flatbuffers@25.12.19": (
        "Apache-2.0",
        "classifier says only 'Apache Software License' and the wheel ships no licence file; every "
        "one of the ten installed modules carries Google's 'Licensed under the Apache License, "
        "Version 2.0' header, read 2026-09-22. A transitive dependency of onnxruntime",
    ),
    "python:skl2onnx@1.20.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; dist-info/licenses/LICENSE is the full "
        "Apache License 2.0 text and NOTICE credits Microsoft, both read 2026-09-22. A transitive "
        "dependency of onnxmltools",
    ),
    "python:cloudpickle@3.1.2": (
        "BSD-3-Clause",
        "classifier says only 'BSD License' and the policy does not read the old-style 'License: "
        "BSD-3-Clause' field; dist-info/licenses/LICENSE is the full 3-clause text (copyright "
        "Cloudpickle contributors, Regents of the University of California, PiCloud), read in "
        "full on 2026-09-22 from the installed wheel, not checked against upstream source. A "
        "transitive dependency of joblib via scikit-learn, not declared directly",
    ),
    "python:nvidia-nccl-cu12@2.31.2": (
        "BSD-3-Clause",
        "the wheel's own License-Expression field says 'LicenseRef-NVIDIA-Proprietary', which is "
        "wrong: dist-info/licenses/License.txt is the 3-clause BSD text for NCCL (NVIDIA "
        "CORPORATION / Lawrence Berkeley National Laboratory / U.S. Department of Energy, DOE "
        "subcontract 7078610), matching the upstream project's own licensing summary "
        "(github.com/NVIDIA/nccl, LICENSE.txt: 'parts of the project retain their original BSD "
        "license'), confirmed 2026-09-22. A transitive, platform-conditional dependency of "
        "xgboost's published wheel (`platform_system == 'Linux' and platform_machine != "
        "'aarch64'`) for its optional GPU code path; `ldd` on the installed libxgboost.so shows it "
        "links no NVIDIA library, and this project trains CPU-only (missing=nan, nthread=1, no "
        "device='cuda' anywhere in the tree), so the wheel is present but never loaded. Recorded "
        "as BSD-3-Clause on the text actually bundled, not on the misleading declared field, "
        "which is the same 'read the file, not the label' rule every other exception here uses",
    ),
    "python:sortedcontainers@2.4.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; METADATA 'License: Apache 2.0' and "
        "dist-info/LICENSE is the Apache License, Version 2.0 notice (verified 2026-09-22). Dev "
        "only, via fakeredis (M5 unit tests without Docker)",
    ),
}

_NAME_PATTERNS: tuple[tuple[str, str], ...] = (
    (
        r"^(the )?apache (software )?licen[cs]e,? (version )?2(\.0)?$|^apache[- ]2(\.0)?$",
        "Apache-2.0",
    ),
    (r"^(the )?mit( licen[cs]e)?$|^expat$|^permission is hereby granted, free of charge", "MIT"),
    # The Eclipse Distribution License 1.0 is the BSD 3-Clause text (SPDX lists it as BSD-3-Clause).
    (r"^(eclipse distribution licen[cs]e,? ?(- )?v(ersion)? ?1\.0|edl 1\.0)$", "BSD-3-Clause"),
    (r"^(bsd[- ]2[- ]clause|simplified bsd)( licen[cs]e)?$", "BSD-2-Clause"),
    (
        r"^(bsd[- ]3[- ]clause|3[- ]clause bsd|new bsd|modified bsd|revised bsd)( licen[cs]e)?$",
        "BSD-3-Clause",
    ),
    (r"^isc( licen[cs]e)?( \(iscl\))?$", "ISC"),
    (r"^(psf|python software foundation)( licen[cs]e)?( 2\.0)?$", "PSF-2.0"),
    (r"^mozilla public licen[cs]e,? (version )?2\.0( \(mpl 2\.0\))?$", "MPL-2.0"),
    (r"^eclipse public licen[cs]e,? ?(- )?v(ersion)? ?2\.0$|^epl 2\.0$", "EPL-2.0"),
    (r"^eclipse public licen[cs]e,? ?(- )?v(ersion)? ?1\.0$|^epl 1\.0$", "EPL-1.0"),
    (
        r"^gnu lesser general public licen[cs]e v2(\.1)? or later( \(lgplv2\+\))?$",
        "LGPL-2.1-or-later",
    ),
    (
        r"^(gnu lesser general public licen[cs]e v2(\.1)?( \(lgplv2\))?|lgplv?2\.1)$",
        "LGPL-2.1-only",
    ),
    (r"^gnu lesser general public licen[cs]e v3 or later( \(lgplv3\+\))?$", "LGPL-3.0-or-later"),
    (r"^(gnu lesser general public licen[cs]e v3( \(lgplv3\))?|lgplv?3(\.0)?)$", "LGPL-3.0-only"),
    (r"^gnu affero general public licen[cs]e v3 or later( \(agplv3\+\))?$", "AGPL-3.0-or-later"),
    (r"^(gnu affero general public licen[cs]e v3( \(agplv3\))?|agplv?3(\.0)?)$", "AGPL-3.0-only"),
    (r"^gnu general public licen[cs]e v2 or later( \(gplv2\+\))?$", "GPL-2.0-or-later"),
    (r"^(gnu general public licen[cs]e v2( \(gplv2\))?|gplv?2(\.0)?)$", "GPL-2.0-only"),
    (r"^gnu general public licen[cs]e v3 or later( \(gplv3\+\))?$", "GPL-3.0-or-later"),
    (r"^(gnu general public licen[cs]e v3( \(gplv3\))?|gplv?3(\.0)?)$", "GPL-3.0-only"),
    (r"^(server side public licen[cs]e|sspl)( v1| 1\.0)?$", "SSPL-1.0"),
    (r"^(blueoak-1\.0\.0|blue oak model licen[cs]e 1\.0\.0)$", "BlueOak-1.0.0"),
    (r"^(cc0-1\.0|cc0 1\.0 universal)$", "CC0-1.0"),
)
_COMPILED_NAMES = tuple((re.compile(p, re.IGNORECASE), spdx) for p, spdx in _NAME_PATTERNS)
_DEPRECATED_SPDX = {
    "GPL-2.0": "GPL-2.0-only",
    "GPL-2.0+": "GPL-2.0-or-later",
    "GPL-3.0": "GPL-3.0-only",
    "GPL-3.0+": "GPL-3.0-or-later",
    "LGPL-2.1": "LGPL-2.1-only",
    "LGPL-2.1+": "LGPL-2.1-or-later",
    "LGPL-3.0": "LGPL-3.0-only",
    "LGPL-3.0+": "LGPL-3.0-or-later",
    "AGPL-3.0": "AGPL-3.0-only",
}


@dataclass(frozen=True)
class Dependency:
    ecosystem: str
    name: str
    version: str
    scope: str
    declared: tuple[str, ...]
    # How several declared licences combine: "any" (dual licensing, e.g. Python classifiers)
    # or "all" (every declared licence applies, e.g. several <license> entries in a Maven POM).
    combine: str = "any"


def normalise(raw: str) -> str | None:
    """SPDX identifier for a single licence name or identifier, or None if not recognised."""
    text = " ".join(raw.split()).strip()
    candidate = _DEPRECATED_SPDX.get(text, text)
    if candidate in KNOWN:
        return candidate
    for pattern, spdx in _COMPILED_NAMES:
        if pattern.search(text):
            return spdx
    return None


_TOKEN = re.compile(r"\(|\)|[^\s()]+")


class _Parser:
    """Recursive-descent parser for SPDX expressions: OR binds loosest, then AND, then WITH."""

    def __init__(self, text: str) -> None:
        self.tokens = _TOKEN.findall(text)
        self.position = 0

    def _peek(self) -> str | None:
        return self.tokens[self.position] if self.position < len(self.tokens) else None

    def _take(self) -> str:
        token = self._peek()
        if token is None:
            raise ValueError("unexpected end of licence expression")
        self.position += 1
        return token

    def parse(self) -> bool | None:
        value = self._or()
        if self._peek() is not None:
            raise ValueError(f"unexpected token {self._peek()!r}")
        return value

    def _or(self) -> bool | None:
        values = [self._and()]
        while self._peek() == "OR":
            self._take()
            values.append(self._and())
        if any(v is True for v in values):
            return True
        return False if all(v is False for v in values) else None

    def _and(self) -> bool | None:
        values = [self._with()]
        while self._peek() == "AND":
            self._take()
            values.append(self._with())
        if any(v is False for v in values):
            return False
        return True if all(v is True for v in values) else None

    def _with(self) -> bool | None:
        value = self._atom()
        if self._peek() == "WITH":
            self._take()
            exception = self._take()
            if exception not in ALLOWED_WITH_EXCEPTIONS:
                return None if value is not False else False
        return value

    def _atom(self) -> bool | None:
        symbol = self._take()
        if symbol == "(":
            value = self._or()
            if self._take() != ")":
                raise ValueError("unbalanced parentheses")
            return value
        spdx = normalise(symbol)
        if spdx is None:
            return None
        return spdx in self.allowed

    allowed: frozenset[str] = frozenset()


def _evaluate_expression(expression: str, allowed: frozenset[str]) -> bool | None:
    # A licence *name* (free text such as "GNU General Public License v3 (GPLv3)") is matched
    # as a whole; anything else is parsed as an SPDX expression.
    whole = normalise(expression)
    if whole is not None:
        return whole in allowed
    parser = _Parser(expression)
    parser.allowed = allowed
    try:
        return parser.parse()
    except ValueError:
        return None


def evaluate(dependency: Dependency) -> bool | None:
    """True if allowed for the dependency's scope, False if not, None if unidentified."""
    allowed = ALLOWED[dependency.scope]
    key = f"{dependency.ecosystem}:{dependency.name}@{dependency.version}"
    if key in EXCEPTIONS:
        return _evaluate_expression(EXCEPTIONS[key][0], allowed)
    values = [_evaluate_expression(d, allowed) for d in dependency.declared if d.strip()]
    if not values:
        return None
    if dependency.combine == "all":
        if any(v is False for v in values):
            return False
        return True if all(v is True for v in values) else None
    if any(v is True for v in values) and all(v is not None for v in values):
        return True
    return False if all(v is False for v in values) else None


def violations(dependencies: list[Dependency]) -> list[str]:
    found: list[str] = []
    for dep in dependencies:
        verdict = evaluate(dep)
        label = f"{dep.ecosystem}:{dep.name}@{dep.version} ({dep.scope})"
        if verdict is None:
            found.append(f"{label}: unidentified licence {dep.declared}")
        elif verdict is False:
            found.append(f"{label}: {dep.declared} not allowed for {dep.scope}")
    return found


# --- collectors ---------------------------------------------------------------------------


def _run(args: list[str], cwd: Path) -> str:
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, check=True).stdout  # noqa: S603


def python_dependencies(root: Path) -> list[Dependency]:
    exported = _run(
        [
            "uv",
            "export",
            "--package",
            "fraudshield-ml",
            "--no-dev",
            "--no-hashes",
            "--no-emit-workspace",
            "--frozen",
        ],
        root,
    )
    runtime = {
        line.split("==")[0].strip().lower()
        for line in exported.splitlines()
        if "==" in line and not line.startswith("#")
    }
    found: list[Dependency] = []
    for dist in importlib.metadata.distributions():
        metadata = dist.metadata
        name = metadata["Name"]
        if name.lower().startswith("fraudshield-"):
            continue
        # Most authoritative source only: SPDX expression, else classifiers, else free text.
        classifiers = [
            c.split("::")[-1].strip()
            for c in metadata.get_all("Classifier") or []
            if c.startswith("License ::")
        ]
        if metadata.get("License-Expression"):
            declared = [metadata["License-Expression"]]
        elif classifiers:
            declared = classifiers
        else:
            declared = (metadata.get("License") or "").strip().splitlines()[:1]
        scope = "runtime" if name.lower() in runtime else "dev"
        found.append(
            Dependency("python", name, dist.version, scope, tuple(d for d in declared if d))
        )
    return found


def node_dependencies(root: Path) -> list[Dependency]:
    frontend = root / "frontend"

    def listed(*flags: str) -> dict[tuple[str, str], str]:
        report = json.loads(_run(["pnpm", "licenses", "list", "--json", *flags], frontend) or "{}")
        return {
            (package["name"], version): licence
            for licence, packages in report.items()
            for package in packages
            for version in package["versions"]
        }

    everything = listed()
    production = listed("--prod")
    return [
        Dependency(
            "npm", name, version, "runtime" if (name, version) in production else "dev", (licence,)
        )
        for (name, version), licence in sorted(everything.items())
    ]


_THIRD_PARTY_LINE = re.compile(
    r"^\s*(?P<licences>(?:\([^()]*\)\s*)+)\S.*\((?P<gav>[^ ():]+:[^ ():]+:[^ ():]+)"
)


class InventoryError(RuntimeError):
    """Raised when a licence report cannot be read completely."""


def parse_third_party_report(text: str) -> dict[str, tuple[str, ...]]:
    """Parse license-maven-plugin THIRD-PARTY output, verifying the declared dependency count."""
    if text.strip() == "The project has no dependencies.":
        return {}
    declared = re.search(r"Lists of (\d+) third-party dependencies", text)
    found: dict[str, tuple[str, ...]] = {}
    for line in text.splitlines():
        match = _THIRD_PARTY_LINE.match(line)
        if match:
            found[match.group("gav")] = tuple(re.findall(r"\(([^()]*)\)", match.group("licences")))
    if declared is None or int(declared.group(1)) != len(found):
        expected = declared.group(1) if declared else "unknown"
        raise InventoryError(f"parsed {len(found)} Maven dependencies, report declares {expected}")
    return found


def _maven_report(root: Path, scopes: str) -> dict[str, tuple[str, ...]]:
    backend = root / "backend"
    filename = f"THIRD-PARTY-{scopes.replace(',', '-')}.txt"
    _run(
        [
            "./mvnw",
            "-B",
            "-ntp",
            "-q",
            "org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party",
            f"-Dlicense.includedScopes={scopes}",
            f"-Dlicense.thirdPartyFilename={filename}",
            "-Dlicense.force=true",
        ],
        backend,
    )
    report = backend / "target/generated-sources/license" / filename
    return parse_third_party_report(report.read_text(encoding="utf-8"))


def java_dependencies(root: Path) -> list[Dependency]:
    runtime = _maven_report(root, "compile,runtime")
    everything = _maven_report(root, "compile,runtime,provided,test,system")
    dependencies: list[Dependency] = []
    for gav, licences in sorted(everything.items()):
        group, artifact, version = gav.split(":")
        scope = "runtime" if gav in runtime else "dev"
        dependencies.append(
            Dependency("maven", f"{group}:{artifact}", version, scope, licences, combine="all")
        )
    return dependencies


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="write the inventory as JSON")
    args = parser.parse_args(argv)
    dependencies = [
        *python_dependencies(REPO_ROOT),
        *node_dependencies(REPO_ROOT),
        *java_dependencies(REPO_ROOT),
    ]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps([asdict(d) for d in dependencies], indent=2) + "\n", encoding="utf-8"
        )
    problems = violations(dependencies)
    for problem in problems:
        print(f"ERROR {problem}", file=sys.stderr)
    counts = {scope: sum(d.scope == scope for d in dependencies) for scope in ("runtime", "dev")}
    print(f"licences: {len(dependencies)} dependencies {counts}, {len(problems)} violations")
    # The Python runtime scope was empty from M0 until 2026-09-19, so this check passed on every
    # commit through three milestones while carrying no information: an empty input satisfies
    # almost any predicate. The first real input failed it. An empty scope is therefore reported
    # as a warning in its own right, so a green run over nothing cannot be mistaken for a green
    # run over something (ADR 0009, 2026-09-19 amendment).
    for scope, count in counts.items():
        if count == 0:
            print(
                f"WARNING the {scope} scope is empty, so this run checked nothing for it; "
                "a pass here means 'had nothing to check', not 'checked and was clean'",
                file=sys.stderr,
            )
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
