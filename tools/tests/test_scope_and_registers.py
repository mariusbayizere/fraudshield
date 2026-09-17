from __future__ import annotations

from pathlib import Path

import pytest

from fraudshield_tools import REPO_ROOT, defect_register, scope_guard
from fraudshield_tools.defect_register import (
    PROMPT_PATH,
    DefectRegisterError,
    defect_ids,
    extract_part_b,
    render,
)
from fraudshield_tools.repo import tracked_files
from fraudshield_tools.scope_guard import LINE_PRAGMA, violations
from fraudshield_tools.scope_terms import banned_terms_in
from fraudshield_tools.traceability_seed import REQUIREMENTS_YAML, SRS_MD, build_rows
from fraudshield_tools.traceability_seed import main as seed_main

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
    compound = "Kinya" + "Med"
    assert banned_terms_in(f"{compound} and {compound.lower()}") == [compound.lower()]


@pytest.mark.req("D-47")
def test_seeded_rows_apply_scope_substitutions() -> None:
    rows = build_rows(SRS_MD.read_text(encoding="utf-8"), PROMPT_PATH.read_text(encoding="utf-8"))
    assert len(rows) == 60 + 147 + 51
    carrying_terms = {r["id"] for r in rows if banned_terms_in(f"{r['title']} {r['srs_text']}")}
    assert carrying_terms == {"D-47"}
    headers = {r["id"]: r["srs_text"] for r in rows}
    assert headers["ML-GATE-01"].startswith("Threshold: 0.940; Financial Justification:")


def test_defect_register_is_complete_and_current() -> None:
    prompt = PROMPT_PATH.read_text(encoding="utf-8")
    assert defect_ids(extract_part_b(prompt)) == [f"D-{n:02d}" for n in range(1, 52)]
    register = (REPO_ROOT / "docs/srs/defect_register.md").read_text(encoding="utf-8")
    assert register == render(prompt)


@pytest.mark.req("D-47")
def test_allowlist_and_line_pragma(tmp_path: Path) -> None:
    review = tmp_path / "docs/reviews/M0/r.md"
    review.parent.mkdir(parents=True)
    review.write_text(f"mutation added the word {_TRI}\n")
    walkthrough = tmp_path / "docs/walkthrough/q.md"
    walkthrough.parent.mkdir(parents=True)
    walkthrough.write_text(
        f"Why does 05B mention {_PAT}s? <!-- {LINE_PRAGMA} -->\nordinary {_TRI} line\n"
    )
    wrong_pragma = tmp_path / "docs/walkthrough/w.md"
    wrong_pragma.write_text(f"{_PAT} <!-- scope-guard: allow -->\n")

    found = violations(tmp_path, [review, walkthrough, wrong_pragma])

    assert found == {"docs/walkthrough/q.md": [_TRI], "docs/walkthrough/w.md": [_PAT]}


def _seed_tree(tmp_path: Path) -> Path:
    for rel in (
        "docs/srs/FraudShield_SRS_v1_0.md",
        "docs/prompts/FraudShield_Master_Build_Prompt.md",
    ):
        (tmp_path / rel).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / rel).write_text((REPO_ROOT / rel).read_text(encoding="utf-8"), encoding="utf-8")
    target = tmp_path / REQUIREMENTS_YAML.relative_to(REPO_ROOT)
    target.parent.mkdir(parents=True)
    target.write_text(REQUIREMENTS_YAML.read_text(encoding="utf-8"), encoding="utf-8")
    return target


def test_seed_check_passes_on_committed_yaml() -> None:
    assert seed_main(["--check"]) == 0


@pytest.mark.parametrize(
    ("old", "new"),
    [
        ("- id: FR-01-03\n  section: 3.1 FR-01 Transaction ingestion API\n", None),
        ("  priority: M\n  milestone: M6\n", "  priority: S\n  milestone: M12\n"),
    ],
)
def test_seed_check_detects_edited_or_deleted_rows(
    tmp_path: Path, old: str, new: str | None
) -> None:
    target = _seed_tree(tmp_path)
    text = target.read_text(encoding="utf-8")
    assert old in text
    if new is None:
        start = text.index(old)
        end = text.index("\n- id: ", start + 1) + 1
        text = text[:start] + text[end:]
    else:
        text = text.replace(old, new, 1)
    target.write_text(text, encoding="utf-8")

    assert seed_main(["--check"], root=tmp_path) == 1
    assert seed_main([], root=tmp_path) == 0
    assert seed_main(["--check"], root=tmp_path) == 0


def test_defect_register_rejects_missing_part_b_and_gaps() -> None:
    with pytest.raises(DefectRegisterError):
        extract_part_b("no part markers here")
    prompt = PROMPT_PATH.read_text(encoding="utf-8")
    with pytest.raises(DefectRegisterError, match="D-17"):
        render(prompt.replace("**D-17 —", "**D-17b —"))


def test_defect_register_cli_check_and_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    register = tmp_path / "defect_register.md"
    monkeypatch.setattr(defect_register, "REGISTER_PATH", register)
    monkeypatch.setattr(defect_register, "REPO_ROOT", tmp_path)

    assert defect_register.main(["--check"]) == 1
    assert "stale" in capsys.readouterr().err
    assert defect_register.main([]) == 0
    assert defect_register.main(["--check"]) == 0


def test_scope_guard_cli_reports_violations(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    offending = tmp_path / "notes.md"
    offending.write_text(f"{_TRI}\n")
    monkeypatch.setattr(scope_guard, "REPO_ROOT", tmp_path)
    monkeypatch.setattr(scope_guard, "tracked_files", lambda _root: [offending])

    assert scope_guard.main() == 1
    assert "notes.md" in capsys.readouterr().err
    offending.write_text("alert review\n")
    assert scope_guard.main() == 0
