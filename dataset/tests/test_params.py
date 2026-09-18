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


@pytest.mark.req("D-08")
@pytest.mark.parametrize(
    ("citation", "expected"),
    [
        ("http://x/2024", "characters"),
        ("Some Central Bank, Annual Report, 2024, https://bank.example.org/report.pdf", "where in"),
        (
            "Bank of Somewhere, Payment Systems Annual Report for 2024 (2025), the bit near the "
            "start about wallets, https://bank.example.org/report.pdf",
            "where in",
        ),
    ],
)
def test_a_citation_too_vague_to_check_is_rejected(
    tmp_path: Path, citation: str, expected: str
) -> None:
    """A SOURCED citation must name the document and where in it the figure is (MAJOR 3.3)."""
    _write(
        tmp_path,
        "volume",
        {
            "rows": {
                "value": 1,
                "unit": "rows",
                "provenance": "SOURCED",
                "citation": citation,
                "rationale": "A rationale long enough to satisfy the twenty character rule.",
                "accessed": dt.date(2026, 1, 1),
            }
        },
    )
    with pytest.raises(ParameterError, match=expected):
        load_parameters(tmp_path)


@pytest.mark.req("D-08")
def test_sourced_needs_a_rationale_and_a_past_access_date(tmp_path: Path) -> None:
    citation = (
        "Bank of Somewhere, Payment Systems Annual Report for 2024 (2025), Annex H, Table H1, "
        "page 47, https://bank.example.org/report.pdf"
    )
    base = {
        "value": 1,
        "unit": "rows",
        "provenance": "SOURCED",
        "citation": citation,
        "accessed": dt.date(2026, 1, 1),
    }
    _write(tmp_path, "volume", {"rows": base})
    with pytest.raises(ParameterError, match="needs a rationale"):
        load_parameters(tmp_path)

    _write(
        tmp_path,
        "volume",
        {
            "rows": {
                **base,
                "rationale": "A rationale long enough to satisfy the twenty character rule.",
                "accessed": dt.date(2099, 12, 31),
            }
        },
    )
    with pytest.raises(ParameterError, match="in the future"):
        load_parameters(tmp_path)


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
    (tmp_path / "unparseable.yaml").write_text("a: [1, 2\n", encoding="utf-8")
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
        "unparseable.yaml: not valid YAML",
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
                "citation": (
                    "National Institute of Statistics, Example Census Report (2022), Table 1, "
                    "page 30, https://example.org/census.pdf"
                ),
                "rationale": "Read from the table; the census counts residents, not adults.",
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
    with pytest.raises(ParameterError):
        parameters.numbers("typed.mix")
    with pytest.raises(ParameterError):
        parameters.texts("typed.mix")
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
