from __future__ import annotations

from pathlib import Path

import pytest

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.defect_register import PROMPT_PATH, defect_ids, extract_part_b, render
from fraudshield_tools.scope_guard import violations
from fraudshield_tools.scope_terms import banned_terms_in
from fraudshield_tools.traceability import tracked_files
from fraudshield_tools.traceability_seed import SRS_MD, build_rows

# Assembled at runtime so this file does not itself contain the out-of-scope words.
_PAT = "pati" + "ent"
_TRI = "tri" + "age"


@pytest.mark.req("D-47")
def test_repository_contains_no_out_of_scope_terms() -> None:
    assert violations(REPO_ROOT, tracked_files(REPO_ROOT)) == {}


@pytest.mark.req("D-47")
def test_guard_detects_terms_in_content_and_paths(tmp_path: Path) -> None:
    content = tmp_path / "frontend/src/Card.tsx"
    content.parent.mkdir(parents=True)
    content.write_text(f"export const label = 'Critical {_TRI.upper()}';\n")
    named = tmp_path / f"docs/{_PAT}_flow.md"
    named.parent.mkdir(parents=True)
    named.write_text("alert flow\n")
    exempt = tmp_path / "docs/srs/copy.md"
    exempt.parent.mkdir(parents=True)
    exempt.write_text(f"{_PAT}s\n")

    found = violations(tmp_path, [content, named, exempt])

    assert found == {"frontend/src/Card.tsx": [_TRI], f"docs/{_PAT}_flow.md": [_PAT]}


def test_banned_terms_respect_word_boundaries() -> None:
    assert banned_terms_in("impatiently outpatient") == []
    assert banned_terms_in(f"{_PAT.title()}s wait") == [_PAT]
    assert banned_terms_in(f"<{_PAT.title()}Card /> {_TRI}_queue") == [_PAT, _TRI]


@pytest.mark.req("D-47")
def test_seeded_rows_apply_scope_substitutions() -> None:
    rows = build_rows(SRS_MD.read_text(encoding="utf-8"), PROMPT_PATH.read_text(encoding="utf-8"))
    assert len(rows) == 60 + 147 + 51
    assert all(banned_terms_in(f"{r['title']} {r['srs_text']}") == [] for r in rows)
    headers = {r["id"]: r["srs_text"] for r in rows}
    assert headers["ML-GATE-01"].startswith("Threshold: 0.940; Financial Justification:")


def test_defect_register_is_complete_and_current() -> None:
    prompt = PROMPT_PATH.read_text(encoding="utf-8")
    assert defect_ids(extract_part_b(prompt)) == [f"D-{n:02d}" for n in range(1, 52)]
    register = (REPO_ROOT / "docs/srs/defect_register.md").read_text(encoding="utf-8")
    assert register == render(prompt)
