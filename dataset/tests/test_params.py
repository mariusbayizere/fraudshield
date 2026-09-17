from __future__ import annotations

import datetime as dt
from pathlib import Path

import pytest
import yaml

from fraudshield_dataset import cli
from fraudshield_dataset.params import ParameterError, Provenance, load_parameters
from fraudshield_dataset.paths import PARAMS_DIR, PROVENANCE_MD
from fraudshield_dataset.provenance_report import render

pytestmark = pytest.mark.req("D-08", "RES-02")


def _write(directory: Path, name: str, parameters: dict[str, object]) -> Path:
    path = directory / f"{name}.yaml"
    path.write_text(
        yaml.safe_dump({"description": f"{name} parameters", "parameters": parameters}),
        encoding="utf-8",
    )
    return path


def test_repository_parameters_are_valid_and_the_report_is_current() -> None:
    parameters = load_parameters()
    assert len(parameters) > 0
    assert PROVENANCE_MD.read_text(encoding="utf-8") == render(parameters)


def test_srs_targets_are_calibrated_not_sourced_or_assumed() -> None:
    parameters = load_parameters()
    for key in (
        "fraud.fraud_rate_overall",
        "fraud.fraud_rate_test",
        "channels.channel_share",
        "geography.country_share",
        "volume.total_rows_target",
    ):
        assert parameters.get(key).provenance is Provenance.CALIBRATED_TO_SRS_TARGET, key


def test_mixes_sum_to_one_and_keep_the_srs_bounds() -> None:
    parameters = load_parameters()
    channels = parameters.mapping("channels.channel_share")
    countries = parameters.mapping("geography.country_share")
    assert sum(channels.values()) == pytest.approx(1.0)
    assert sum(countries.values()) == pytest.approx(1.0)
    assert channels["MOBILE_MONEY"] >= 0.40
    assert channels["USSD"] >= 0.18
    assert channels["AGENT_BANKING"] >= 0.14


def test_every_problem_is_reported_at_once(tmp_path: Path) -> None:
    _write(
        tmp_path,
        "broken",
        {
            "no_unit": {"value": 1, "provenance": "ASSUMED", "rationale": "x" * 30},
            "sourced_without_url": {
                "value": 1,
                "unit": "u",
                "provenance": "SOURCED",
                "citation": "A report, 2023",
            },
            "assumed_with_citation": {
                "value": 1,
                "unit": "u",
                "provenance": "ASSUMED",
                "rationale": "a modelling choice explained here",
                "citation": "looks sourced",
            },
            "assumed_without_rationale": {"value": 1, "unit": "u", "provenance": "ASSUMED"},
            "calibrated_without_row": {
                "value": 1,
                "unit": "u",
                "provenance": "CALIBRATED_TO_SRS_TARGET",
                "citation": "somewhere",
            },
            "unknown_provenance": {"value": 1, "unit": "u", "provenance": "GUESSED"},
            "extra_field": {
                "value": 1,
                "unit": "u",
                "provenance": "ASSUMED",
                "rationale": "a modelling choice explained here",
                "source": "x",
            },
            "Bad-Name": {"value": 1},
            "not_a_mapping": 3,
            "accessed_on_assumed": {
                "value": 1,
                "unit": "u",
                "provenance": "ASSUMED",
                "rationale": "a modelling choice explained here",
                "accessed": dt.date(2026, 1, 1),
            },
        },
    )
    (tmp_path / "shapeless.yaml").write_text("- just a list\n", encoding="utf-8")
    with pytest.raises(ParameterError) as caught:
        load_parameters(tmp_path)
    message = str(caught.value)
    for expected in (
        "broken.no_unit: missing unit",
        "broken.sourced_without_url: SOURCED needs a citation with the year and a URL",
        "broken.sourced_without_url: SOURCED needs accessed",
        "broken.assumed_with_citation: ASSUMED must not carry a citation",
        "broken.assumed_without_rationale: ASSUMED needs a rationale",
        "broken.calibrated_without_row: CALIBRATED_TO_SRS_TARGET needs a citation naming",
        "broken.unknown_provenance: provenance must be one of",
        "broken.extra_field: unknown field 'source'",
        "broken.Bad-Name: parameter names are lower_snake_case",
        "broken.not_a_mapping: expected a mapping",
        "broken.accessed_on_assumed: accessed applies to SOURCED parameters only",
        "shapeless.yaml: expected 'description'",
    ):
        assert expected in message, expected


def test_a_complete_sourced_parameter_loads(tmp_path: Path) -> None:
    _write(
        tmp_path,
        "population",
        {
            "rwanda_population": {
                "value": 13246394,
                "unit": "persons",
                "provenance": "SOURCED",
                "citation": "Example census report, table 1, 2022, https://example.org/census",
                "accessed": dt.date(2026, 9, 17),
            }
        },
    )
    parameters = load_parameters(tmp_path)
    parameter = parameters.get("population.rwanda_population")
    assert parameters.integer("population.rwanda_population") == 13246394
    assert "accessed 2026-09-17" in render(parameters)
    assert parameter.key == "population.rwanda_population"


def test_typed_accessors_reject_the_wrong_type(tmp_path: Path) -> None:
    _write(
        tmp_path,
        "typed",
        {
            name: {"value": value, "unit": "u", "provenance": "ASSUMED", "rationale": "r" * 25}
            for name, value in {"flag": True, "text": "x", "ratio": 0.5, "mix": {"a": 1}}.items()
        },
    )
    parameters = load_parameters(tmp_path)
    assert parameters.number("typed.ratio") == 0.5
    assert parameters.mapping("typed.mix") == {"a": 1.0}
    with pytest.raises(ParameterError):
        parameters.number("typed.flag")
    with pytest.raises(ParameterError):
        parameters.integer("typed.ratio")
    with pytest.raises(ParameterError):
        parameters.mapping("typed.text")
    with pytest.raises(KeyError, match="unknown generator parameter"):
        parameters.get("typed.missing")
    with pytest.raises(ParameterError, match="no parameter files"):
        load_parameters(tmp_path / "empty")


def test_cli_writes_and_checks_the_report(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "params_provenance.md"
    monkeypatch.setattr(cli, "PROVENANCE_MD", target)
    assert cli.main(["provenance", "--check"]) == 1
    assert cli.main(["provenance"]) == 0
    assert cli.main(["provenance", "--check"]) == 0
    assert "up to date" in capsys.readouterr().out
    monkeypatch.setattr(cli, "load_parameters", lambda: load_parameters(tmp_path / "none"))
    assert cli.main(["provenance"]) == 2
    assert PARAMS_DIR.is_dir()
