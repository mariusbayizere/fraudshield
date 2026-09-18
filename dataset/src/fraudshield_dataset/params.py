"""Generator parameters with mandatory provenance (ADR 0022, owner direction 2026-09-17).

Every number the generator uses lives in ``dataset/generator/params/<category>.yaml``::

    description: What this category controls.
    parameters:
      fraud_rate_overall:
        value: 0.0087
        unit: fraction of transactions
        provenance: CALIBRATED_TO_SRS_TARGET
        citation: SRS v1.0 section 7.1, ML-DATA-02 (0.87% overall)

Provenance is one of:

- ``SOURCED``: taken from a document the author actually read. Needs ``citation`` naming the
  document, its publisher, the year, a URL and *where in it* the figure is (a table, page, figure,
  annex, section, indicator or version); ``accessed`` (an ISO date, not in the future); and a
  ``rationale`` saying how the value follows from those figures and what the source does not
  establish. The loader cannot tell whether a document was read, but it can refuse a citation too
  vague to check (M2 principal review, MAJOR 3.3).
- ``ASSUMED``: a modelling choice without a source. Needs ``rationale``; must never be presented
  as sourced.
- ``CALIBRATED_TO_SRS_TARGET``: set to meet an SRS 7.1 target. Needs ``citation`` naming the SRS
  row; ``rationale`` explains any adjustment (for example rounding a mix to 100%).

The loader rejects files that break these rules, listing every problem, so a parameter cannot be
used without its provenance.
"""

from __future__ import annotations

import datetime as dt
import re
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any

import yaml

from fraudshield_dataset.paths import PARAMS_DIR

Value = bool | int | float | str | list[Any] | dict[str, Any]

_NAME = re.compile(r"^[a-z][a-z0-9_]*(\.[A-Za-z0-9_]+)*$")
# A citation has to say *where* in the document the figure is, not merely that a document exists.
_LOCATOR = re.compile(
    r"\b(table|page|pages|figure|annex|annexure|appendix|section|chapter|indicator|list|column|"
    r"row|version|paragraph|clause)\b",
    re.IGNORECASE,
)
MIN_CITATION_CHARACTERS = 80
_URL = re.compile(r"https?://\S+")
_YEAR = re.compile(r"\b(19|20)\d{2}\b")
_SRS_ROW = re.compile(r"\b(ML-DATA-\d{2}|RES-\d{2}|D-\d{2})\b|section 7\.1")
_FIELDS = {"value", "unit", "provenance", "citation", "rationale", "accessed"}


class Provenance(StrEnum):
    SOURCED = "SOURCED"
    ASSUMED = "ASSUMED"
    CALIBRATED_TO_SRS_TARGET = "CALIBRATED_TO_SRS_TARGET"


@dataclass(frozen=True)
class Parameter:
    category: str
    name: str
    value: Value
    unit: str
    provenance: Provenance
    citation: str | None
    rationale: str | None
    accessed: dt.date | None

    @property
    def key(self) -> str:
        return f"{self.category}.{self.name}"


class ParameterError(ValueError):
    """A parameter file breaks the provenance rules."""


class ScaleError(ParameterError):
    """The run is too small for the parameters, which are themselves fine.

    Separate from :class:`ParameterError` because the repair is different: the operator should
    raise the row count, not edit a sourced or calibrated share. It subclasses it so that every
    existing handler still catches it.
    """


@dataclass(frozen=True)
class ParameterSet:
    parameters: Mapping[str, Parameter]
    descriptions: Mapping[str, str]

    def __iter__(self) -> Iterator[Parameter]:
        return iter(self.parameters.values())

    def __len__(self) -> int:
        return len(self.parameters)

    def get(self, key: str) -> Parameter:
        try:
            return self.parameters[key]
        except KeyError:
            raise KeyError(f"unknown generator parameter {key!r}") from None

    def value(self, key: str) -> Value:
        return self.get(key).value

    def number(self, key: str) -> float:
        value = self.value(key)
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise ParameterError(f"{key}: expected a number, got {value!r}")
        return float(value)

    def integer(self, key: str) -> int:
        value = self.value(key)
        if isinstance(value, bool) or not isinstance(value, int):
            raise ParameterError(f"{key}: expected an integer, got {value!r}")
        return value

    def mapping(self, key: str) -> dict[str, float]:
        value = self.value(key)
        if not isinstance(value, dict) or not all(
            isinstance(v, int | float) and not isinstance(v, bool) for v in value.values()
        ):
            raise ParameterError(f"{key}: expected a mapping of numbers, got {value!r}")
        return {str(k): float(v) for k, v in value.items()}

    def texts(self, key: str) -> dict[str, str]:
        value = self.value(key)
        if not isinstance(value, dict) or not all(isinstance(v, str) for v in value.values()):
            raise ParameterError(f"{key}: expected a mapping of strings, got {value!r}")
        return {str(k): v for k, v in value.items()}

    def numbers(self, key: str) -> list[float]:
        value = self.value(key)
        if not isinstance(value, list) or not all(
            isinstance(v, int | float) and not isinstance(v, bool) for v in value
        ):
            raise ParameterError(f"{key}: expected a list of numbers, got {value!r}")
        return [float(v) for v in value]

    def categories(self) -> list[str]:
        return sorted(self.descriptions)


def _sourced_problems(where: str, raw: dict[str, Any]) -> list[str]:
    """What a SOURCED parameter must carry beyond a value (M2 principal review, MAJOR 3.3)."""
    citation = raw.get("citation")
    rationale = raw.get("rationale")
    accessed = raw.get("accessed")
    problems: list[str] = []
    if not (isinstance(citation, str) and _URL.search(citation) and _YEAR.search(citation)):
        problems.append(f"{where}: SOURCED needs a citation with the year and a URL")
    elif len(citation.strip()) < MIN_CITATION_CHARACTERS:
        problems.append(
            f"{where}: SOURCED citation is {len(citation.strip())} characters; name the document, "
            f"its publisher and where in it the figure is ({MIN_CITATION_CHARACTERS}+ characters)"
        )
    elif not _LOCATOR.search(citation):
        problems.append(
            f"{where}: SOURCED citation must say where in the document the figure is "
            "(table, page, figure, annex, section, indicator or version)"
        )
    if not (isinstance(rationale, str) and len(rationale.strip()) >= 20):
        problems.append(
            f"{where}: SOURCED needs a rationale too, saying how the value follows from the "
            "figures and what the source does not establish"
        )
    if not isinstance(accessed, dt.date):
        problems.append(f"{where}: SOURCED needs accessed: YYYY-MM-DD")
    elif accessed > dt.datetime.now(tz=dt.UTC).date():
        problems.append(f"{where}: accessed {accessed} is in the future")
    return problems


def _provenance_problems(where: str, raw: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    provenance = raw.get("provenance")
    citation = raw.get("citation")
    rationale = raw.get("rationale")
    if provenance is not None and provenance not in {p.value for p in Provenance}:
        problems.append(f"{where}: provenance must be one of {[p.value for p in Provenance]}")
    if provenance == Provenance.SOURCED:
        problems.extend(_sourced_problems(where, raw))
    elif provenance == Provenance.ASSUMED:
        if not (isinstance(rationale, str) and len(rationale.strip()) >= 20):
            problems.append(f"{where}: ASSUMED needs a rationale (at least 20 characters)")
        if citation is not None:
            problems.append(f"{where}: ASSUMED must not carry a citation; it is not sourced")
    elif provenance == Provenance.CALIBRATED_TO_SRS_TARGET and not (
        isinstance(citation, str) and _SRS_ROW.search(citation)
    ):
        problems.append(f"{where}: CALIBRATED_TO_SRS_TARGET needs a citation naming the SRS row")
    if "accessed" in raw and provenance != Provenance.SOURCED:
        problems.append(f"{where}: accessed applies to SOURCED parameters only")
    return problems


def _problems_for(category: str, name: str, raw: object) -> list[str]:
    where = f"{category}.{name}"
    if not _NAME.match(name):
        return [f"{where}: parameter names are lower_snake_case, optionally dotted"]
    if not isinstance(raw, dict):
        return [f"{where}: expected a mapping with value, unit and provenance"]
    problems = [f"{where}: unknown field {field!r}" for field in sorted(set(raw) - _FIELDS)]
    for field in ("value", "unit", "provenance"):
        if field not in raw:
            problems.append(f"{where}: missing {field}")
    if "unit" in raw and not (isinstance(raw["unit"], str) and raw["unit"].strip()):
        problems.append(f"{where}: unit must be a non-empty string")
    problems.extend(_provenance_problems(where, raw))
    return problems


def load_parameters(directory: Path = PARAMS_DIR) -> ParameterSet:
    """Load and validate every ``*.yaml`` file in ``directory``."""
    parameters: dict[str, Parameter] = {}
    descriptions: dict[str, str] = {}
    problems: list[str] = []
    files = sorted(directory.glob("*.yaml"))
    if not files:
        raise ParameterError(f"no parameter files in {directory}")
    for path in files:
        category = path.stem
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as error:
            problems.append(f"{path.name}: not valid YAML ({error.__class__.__name__})")
            continue
        if not isinstance(document, dict) or not isinstance(document.get("parameters"), dict):
            problems.append(f"{path.name}: expected 'description' and a 'parameters' mapping")
            continue
        description = document.get("description")
        if not (isinstance(description, str) and description.strip()):
            problems.append(f"{path.name}: missing description")
        descriptions[category] = str(description or "").strip()
        for name, raw in document["parameters"].items():
            found = _problems_for(category, str(name), raw)
            if found:
                problems.extend(found)
                continue
            parameter = Parameter(
                category=category,
                name=str(name),
                value=raw["value"],
                unit=raw["unit"].strip(),
                provenance=Provenance(raw["provenance"]),
                citation=raw.get("citation"),
                rationale=raw.get("rationale"),
                accessed=raw.get("accessed"),
            )
            parameters[parameter.key] = parameter
    if problems:
        raise ParameterError("invalid generator parameters:\n  " + "\n  ".join(problems))
    return ParameterSet(parameters=parameters, descriptions=descriptions)
