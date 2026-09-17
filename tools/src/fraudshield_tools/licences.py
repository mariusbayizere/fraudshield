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
    {"EPL-1.0", "EPL-2.0", "MPL-2.0", "LGPL-2.1-or-later", "LGPL-3.0-or-later", "CC-BY-4.0"}
)
ALLOWED = {"runtime": PERMISSIVE, "dev": PERMISSIVE | DEV_ONLY}

# Reviewed per-package decisions where metadata is missing or ambiguous. Key: ecosystem:name.
EXCEPTIONS: dict[str, tuple[str, str]] = {}

_NORMALISE: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(pattern, re.IGNORECASE), spdx)
    for pattern, spdx in (
        (r"^apache[- ]2\.0$|apache (software )?licen[cs]e,? (version )?2\.0", "Apache-2.0"),
        (r"^mit(-0)?$|^mit licen[cs]e$|permission is hereby granted, free of charge", "MIT"),
        (r"^bsd-2-clause$|simplified bsd", "BSD-2-Clause"),
        (r"^bsd-3-clause$|new bsd|modified bsd|^bsd( licen[cs]e)?$", "BSD-3-Clause"),
        (r"^isc( licen[cs]e)?$", "ISC"),
        (r"^psf-2\.0$|python software foundation", "PSF-2.0"),
        (r"^mpl-2\.0$|mozilla public license 2\.0", "MPL-2.0"),
        (r"^epl-2\.0$|eclipse public license (v|- v)?(ersion )?2\.0", "EPL-2.0"),
        (r"^epl-1\.0$|eclipse public license (v|- v)?(ersion )?1\.0", "EPL-1.0"),
        (r"^blueoak-1\.0\.0$", "BlueOak-1.0.0"),
        (r"^cc0-1\.0$", "CC0-1.0"),
        (r"^cc-by-4\.0$", "CC-BY-4.0"),
        (r"^0bsd$", "0BSD"),
        (r"^zlib$", "Zlib"),
        (r"^unlicense$", "Unlicense"),
    )
)


@dataclass(frozen=True)
class Dependency:
    ecosystem: str
    name: str
    version: str
    scope: str
    declared: tuple[str, ...]


def normalise(raw: str) -> str | None:
    text = " ".join(raw.split())
    for pattern, spdx in _NORMALISE:
        if pattern.search(text):
            return spdx
    return None


def spdx_options(declared: tuple[str, ...]) -> set[str]:
    """Licences the dependency may be used under (any one suffices)."""
    options: set[str] = set()
    for entry in declared:
        for alternative in re.split(r"\s+OR\s+", entry.strip("() ")):
            spdx = normalise(alternative)
            if spdx:
                options.add(spdx)
    return options


def violations(dependencies: list[Dependency]) -> list[str]:
    found: list[str] = []
    for dep in dependencies:
        key = f"{dep.ecosystem}:{dep.name}"
        if key in EXCEPTIONS:
            continue
        options = spdx_options(dep.declared)
        if not options:
            found.append(f"{key}@{dep.version} ({dep.scope}): unidentified licence {dep.declared}")
        elif not options & ALLOWED[dep.scope]:
            found.append(
                f"{key}@{dep.version} ({dep.scope}): {sorted(options)} not allowed for {dep.scope}"
            )
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
        declared = [metadata.get("License-Expression") or "", metadata.get("License") or ""]
        declared += [
            c.split("::")[-1].strip()
            for c in metadata.get_all("Classifier") or []
            if c.startswith("License ::")
        ]
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
        dependencies.append(Dependency("maven", f"{group}:{artifact}", version, scope, licences))
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
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
