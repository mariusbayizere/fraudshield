"""Tests for the generated Grafana dashboards (infrastructure/grafana/generate_dashboards.py)."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "infrastructure/grafana"))

import generate_dashboards as gd  # noqa: E402

BOARDS = [board.render() for board in gd.DASHBOARDS]


def test_committed_json_matches_the_generator(capsys: pytest.CaptureFixture[str]) -> None:
    assert gd.main(["--check"]) == 0, capsys.readouterr().err


def test_uids_and_titles_are_unique() -> None:
    assert len({board["uid"] for board in BOARDS}) == len(BOARDS)
    assert len({board["title"] for board in BOARDS}) == len(BOARDS)


@pytest.mark.parametrize("board", BOARDS, ids=lambda board: board["uid"])
def test_panels_fit_the_grid_and_never_overlap(board: dict[str, Any]) -> None:
    cells: set[tuple[int, int]] = set()
    for panel in board["panels"]:
        grid = panel["gridPos"]
        assert grid["x"] >= 0
        assert grid["x"] + grid["w"] <= gd.GRID_WIDTH
        area = {
            (x, y)
            for x in range(grid["x"], grid["x"] + grid["w"])
            for y in range(grid["y"], grid["y"] + grid["h"])
        }
        assert not cells & area, f"{panel['title']} overlaps another panel"
        cells |= area


@pytest.mark.parametrize("board", BOARDS, ids=lambda board: board["uid"])
def test_every_query_uses_the_provisioned_prometheus_datasource(board: dict[str, Any]) -> None:
    for panel in board["panels"]:
        assert panel["datasource"] == gd.PROMETHEUS
        assert panel["targets"], f"{panel['title']} has no query"
        assert {target["datasource"]["uid"] for target in panel["targets"]} == {"prometheus"}
        ref_ids = [target["refId"] for target in panel["targets"]]
        assert len(set(ref_ids)) == len(ref_ids)


def test_provisioning_uses_the_uid_dashboards_reference() -> None:
    provisioning = ROOT / "infrastructure/grafana/provisioning/datasources/fraudshield.yml"
    uids = {source["uid"] for source in yaml.safe_load(provisioning.read_text())["datasources"]}
    assert gd.PROMETHEUS["uid"] in uids


def test_every_dashboard_named_in_a_runbook_exists() -> None:
    titles = {board["title"] for board in BOARDS}
    named = {
        match
        for path in (ROOT / "docs/runbooks").glob("*.md")
        for match in re.findall(r"\*\*(FraudShield / [^*]+)\*\*", path.read_text())
    }
    assert named, "runbooks name no dashboards; the pattern or the runbooks changed"
    assert named <= titles, f"runbooks name missing dashboards: {sorted(named - titles)}"
