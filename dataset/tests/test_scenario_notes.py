from __future__ import annotations

import re

import pytest

from fraudshield_dataset.generator.fraud import SCENARIOS
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.paths import DATASET_ROOT

pytestmark = pytest.mark.req("ML-DATA-04")

NOTES = DATASET_ROOT.parent / "docs" / "ml" / "scenarios"
_REFERENCE = re.compile(
    r"`((?:behaviour|fraud|population|labels|volume|channels|geography|split|currencies)\.[a-z0-9_.*]+)`"
)


def test_every_scenario_has_a_design_note_listed_in_the_index() -> None:
    index = (NOTES / "README.md").read_text(encoding="utf-8")
    for scenario in SCENARIOS:
        assert (NOTES / f"{scenario}.md").is_file(), scenario
        assert f"({scenario}.md)" in index, scenario


def test_parameters_named_in_the_notes_exist() -> None:
    parameters = load_parameters()
    keys = set(parameters.parameters)
    missing = []
    for note in sorted(NOTES.glob("*.md")):
        for reference in _REFERENCE.findall(note.read_text(encoding="utf-8")):
            if reference.endswith("*"):
                prefix = reference.rstrip("*")
                if not any(k.startswith(prefix) for k in keys):
                    missing.append(f"{note.name}: {reference}")
                continue
            parts = reference.split(".")
            key = ".".join(parts[:2])
            if key not in keys:
                missing.append(f"{note.name}: {reference}")
            elif len(parts) > 2:
                value = parameters.value(key)
                if not (isinstance(value, dict) and parts[2] in value):
                    missing.append(f"{note.name}: {reference}")
    assert missing == []
