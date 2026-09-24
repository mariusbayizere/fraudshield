"""`corridor_class` on both paths, hand-computed (Part E.2, PB-30, ADR 0023; E13).

Part E.2 specifies `DOMESTIC | EAC_CROSS_BORDER | NON_EAC_CROSS_BORDER`. ADR 0023 replaces it
with four bloc-derived classes so that EAC is a set of memberships in a pack rather than a branch
in code. The feature keeps its slot and the count stays 44.

Two properties are proved here that no parity replay can prove, because this feature has no
history and no state: that the classification is **right** against a hand-worked table, and that
it is **pack-driven** — an invented country in an invented bloc classifies correctly with no
change to any source file.
"""

from __future__ import annotations

import inspect
import re
from datetime import UTC, datetime

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features import online as online_module
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.registry import REGISTRY, categories_for
from fraudshield_ml.features.types import CountryFacts, Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)

DOMESTIC, INTRA_BLOC, CROSS_BLOC_AFRICA, INTERCONTINENTAL = categories_for("corridor_class")

#: A source line counts as code when it contains an assignment, a call, a subscript or a branch
#: keyword. Docstring prose naming a bloc then does not trip the scan below, while
#: `blocs = {"EAC"}` does — the scan must be able to discuss the thing it forbids.
_CODE = re.compile(r"[=(\[]|^\s*(return|if|elif|raise)\b")

#: The five validated-core packs' real bloc memberships, read from
#: `dataset/generator/params/countries/*.yaml` at tree 654c6d8, plus three invented countries that
#: exist only to reach the classes the core cannot. `ZW` and `QQ` are **not** country packs and
#: make no claim about anywhere: `ZW` is an invented African country in an invented bloc, `QQ` an
#: invented non-African one. They are the ML-side counterpart of the generator's Country Z test.
PACKS: dict[str, CountryFacts] = {
    "RW": CountryFacts("RW", "AF", frozenset({"EAC", "COMESA"}), 2),
    "KE": CountryFacts("KE", "AF", frozenset({"EAC", "COMESA"}), 3),
    "TZ": CountryFacts("TZ", "AF", frozenset({"EAC", "SADC"}), 3),
    "UG": CountryFacts("UG", "AF", frozenset({"EAC", "COMESA"}), 3),
    "CD": CountryFacts("CD", "AF", frozenset({"EAC", "COMESA", "SADC"}), 1),
    "ZW": CountryFacts("ZW", "AF", frozenset({"ZBLOC"}), 2),
    "QQ": CountryFacts("QQ", "QQ", frozenset({"QBLOC"}), -5),
}


def tx(sender: str | None, recipient: str | None, *, tid: str = "t") -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id="A",
        timestamp=T,
        amount_rwf=1000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        account_country=sender,
        counterparty_country=recipient,
    )


def classify_both(sender: str | None, recipient: str | None) -> str:
    """Both paths, asserted equal under ADR 0025's exact rule for a categorical."""
    scored = tx(sender, recipient)
    batch_value = batch.corridor_class(scored, PACKS)
    online_value = OnlineFeatures().corridor_class(scored, PACKS)
    assert batch_value == online_value, (
        f"{sender}->{recipient}: batch {batch_value!r} vs online {online_value!r}. A categorical "
        "admits no tolerance (ADR 0025), so any difference at all is the bug"
    )
    return batch_value


# --- the hand-worked table ---------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-03")
def test_the_four_classes_against_hand_worked_pairs() -> None:
    """One pair per class, each worked from the two packs' declared facts.

    - RW->RW: same country. DOMESTIC.
    - RW->KE: {EAC, COMESA} & {EAC, COMESA} = {EAC, COMESA}, non-empty. INTRA_BLOC.
    - RW->TZ: {EAC, COMESA} & {EAC, SADC} = {EAC}. Still INTRA_BLOC, and the case that shows
      membership is a SET — the pair shares one bloc while differing on another, which the
      three-value EAC feature could not express.
    - RW->ZW: {EAC, COMESA} & {ZBLOC} = empty, continents both AF. CROSS_BLOC_AFRICA.
    - RW->QQ: no shared bloc and different continents. INTERCONTINENTAL.
    """
    assert classify_both("RW", "RW") == DOMESTIC
    assert classify_both("RW", "KE") == INTRA_BLOC
    assert classify_both("RW", "TZ") == INTRA_BLOC
    assert classify_both("RW", "ZW") == CROSS_BLOC_AFRICA
    assert classify_both("RW", "QQ") == INTERCONTINENTAL


@pytest.mark.req("FR-02-02")
def test_the_fixture_reaches_every_declared_class() -> None:
    """E13's precondition for the table above, asserted rather than assumed.

    A fixture that never produced CROSS_BLOC_AFRICA would leave the third branch untested while
    the table above still read as exhaustive — the shape E12 exists for. Note this is a property
    of *this fixture*, not of the dataset: on the generated benchmark only two of the four classes
    occur at all, which the registry declares as a degeneracy (PB-43).
    """
    pairs = [("RW", "RW"), ("RW", "KE"), ("RW", "TZ"), ("RW", "ZW"), ("RW", "QQ")]
    produced = {classify_both(a, b) for a, b in pairs}
    assert produced == set(categories_for("corridor_class")), (
        f"the fixture produced {sorted(produced)}; a class no pair reaches is a class no test "
        "covers"
    )


@pytest.mark.req("FR-02-02")
def test_a_domestic_corridor_is_not_reported_as_intra_bloc() -> None:
    """The ordering of the first two tests is the definition, not a style choice.

    A country shares every one of its blocs with itself, so a rule that asked "do they share a
    bloc?" first would classify every domestic transaction as INTRA_BLOC — and since the
    overwhelming majority of rows are domestic, the feature would become nearly constant while
    still looking plausible. This asserts the sender's own blocs are non-empty, so the mistake
    would actually be reachable in this fixture.
    """
    assert PACKS["RW"].blocs, (
        "precondition: the sender belongs to at least one bloc, or 'shares a bloc with itself' "
        "is not a mistake this fixture could make (E12)"
    )
    assert PACKS["RW"].blocs & PACKS["RW"].blocs
    assert classify_both("RW", "RW") == DOMESTIC


@pytest.mark.req("FR-02-02")
def test_a_shared_bloc_outranks_a_shared_continent() -> None:
    """TZ and CD share SADC but not COMESA; both are African. INTRA_BLOC, not CROSS_BLOC_AFRICA."""
    assert PACKS["TZ"].blocs & PACKS["CD"].blocs, "precondition: the pair shares a bloc"
    assert PACKS["TZ"].continent == PACKS["CD"].continent, "precondition: same continent too"
    assert classify_both("TZ", "CD") == INTRA_BLOC


@pytest.mark.req("FR-02-02")
def test_the_corridor_is_symmetric_in_this_dataset_but_read_in_one_direction() -> None:
    """Sender and recipient are read as an ordered pair, and the rule happens to be symmetric.

    Recorded because the online path memoises on the ordered pair: if the rule ever stops being
    symmetric — a directional corridor rule, say — this test fails and the memo is already keyed
    correctly for it.
    """
    for a, b in (("RW", "KE"), ("RW", "ZW"), ("RW", "QQ"), ("TZ", "CD")):
        assert classify_both(a, b) == classify_both(b, a)


# --- pack-drivenness (ADR 0023) ------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_an_invented_country_in_an_invented_bloc_needs_no_code_change() -> None:
    """ADR 0023's acceptance test, on the feature side: adding a country is adding a pack.

    `XK` and its bloc `XBLOC` appear nowhere in any source file. The generator's Country Z test
    proves the same property for generation; this proves it for the feature pipeline, which ADR
    0023 names alongside it.
    """
    packs = dict(PACKS)
    packs["XK"] = CountryFacts("XK", "AF", frozenset({"XBLOC", "EAC"}), 1)
    packs["XL"] = CountryFacts("XL", "AF", frozenset({"XBLOC"}), 1)

    scored = tx("XK", "XL")
    assert batch.corridor_class(scored, packs) == INTRA_BLOC
    assert OnlineFeatures().corridor_class(scored, packs) == INTRA_BLOC

    # And the invented country reaches the rest of the table too, through the same code.
    assert batch.corridor_class(tx("XK", "RW"), packs) == INTRA_BLOC  # both in EAC
    assert batch.corridor_class(tx("XL", "RW"), packs) == CROSS_BLOC_AFRICA
    assert batch.corridor_class(tx("XL", "QQ"), packs) == INTERCONTINENTAL


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_no_country_currency_bloc_or_continent_is_named_in_the_feature_paths() -> None:
    """E8, enforced on the source rather than trusted to review.

    The behavioural test above is the real acceptance criterion — a lint can be satisfied by
    renaming a constant — but a literal that creeps back in is worth failing on directly, because
    it would make the behavioural test pass for one pack and fail for the next.
    """
    banned = (
        # The six blocs ADR 0023 names, plus the packs' codes and the continent code.
        "EAC",
        "ECOWAS",
        "SADC",
        "COMESA",
        "CEMAC",
        "AMU",
        "RWF",
        "KES",
        "TZS",
        "UGX",
        "CDF",
    )
    for module in (batch, online_module):
        source = inspect.getsource(module)
        body = "\n".join(line for line in source.splitlines() if not _is_prose(line))
        for literal in banned:
            assert literal not in body, (
                f"{module.__name__} names {literal!r} outside a comment or docstring; under ADR "
                "0023 a country, currency or bloc is pack data and never a case in code"
            )


def _is_prose(line: str) -> bool:
    """Comments and the docstring prose that must be free to *discuss* blocs by name."""
    return line.strip().startswith("#") or not _CODE.search(line)


# --- refusals ------------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_a_missing_country_is_refused_on_both_paths_rather_than_defaulted() -> None:
    """There is no safe default: every substitute names a corridor the transaction did not cross.

    Defaulting the sender to the recipient's country would report DOMESTIC — the class that
    carries the least risk signal — for a transaction whose corridor is unknown.
    """
    for scored in (tx(None, "KE"), tx("RW", None)):
        with pytest.raises(ValueError, match="corridor_class needs"):
            batch.corridor_class(scored, PACKS)
        with pytest.raises(ValueError, match="corridor_class needs"):
            OnlineFeatures().corridor_class(scored, PACKS)


@pytest.mark.req("FR-02-02")
def test_a_country_without_a_pack_is_refused_on_both_paths() -> None:
    """A deployment serving a country it has no pack for is a configuration error, not a row to
    score with a guess."""
    scored = tx("RW", "YY")
    with pytest.raises(KeyError, match="YY"):
        batch.corridor_class(scored, PACKS)
    with pytest.raises(KeyError, match="YY"):
        OnlineFeatures().corridor_class(scored, PACKS)


# --- the online path's memo, which batch has no equivalent of -------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_memo_does_not_conflate_two_corridors_from_the_same_sender() -> None:
    """The one way this feature can be wrong online and right in batch.

    A memo keyed on the sender alone would return the first corridor's class for every later
    recipient. Every hand-computed test above would still pass, because each builds a fresh
    `OnlineFeatures` and classifies one pair.
    """
    features = OnlineFeatures()
    first = features.corridor_class(tx("RW", "KE"), PACKS)
    second = features.corridor_class(tx("RW", "QQ"), PACKS)
    assert first == INTRA_BLOC
    assert second == INTERCONTINENTAL, (
        "the second corridor returned the first one's class, so the memo is keyed on the sender "
        "rather than on the pair"
    )
    assert features.corridor_class(tx("RW", "KE"), PACKS) == INTRA_BLOC, (
        "returning to a memoised pair must give the same answer"
    )


@pytest.mark.req("FR-02-02")
def test_a_cache_flush_does_not_move_the_corridor() -> None:
    """The memo holds pack data, not history, so the cold-cache case must be a no-op here.

    Asserted rather than reasoned about: this is the only feature so far whose online state is
    derived from parameters instead of arrivals, and "it is obviously safe" is the sentence that
    preceded every guard-with-two-doors in this project's notebook.
    """
    features = OnlineFeatures()
    warm = features.corridor_class(tx("RW", "TZ"), PACKS)
    features.flush_cache()
    assert features.corridor_class(tx("RW", "TZ"), PACKS) == warm


# --- the declared contract -----------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-03")
def test_every_value_either_path_emits_is_a_declared_category() -> None:
    """ADR 0025 requires exact equality for a categorical, which is meaningless if the set of
    values is open. Both paths are checked over every ordered pair in the fixture."""
    declared = set(categories_for("corridor_class"))
    codes = sorted(PACKS)
    features = OnlineFeatures()
    seen = set()
    for a in codes:
        for b in codes:
            value = batch.corridor_class(tx(a, b), PACKS)
            assert value == features.corridor_class(tx(a, b), PACKS)
            assert value in declared, f"{a}->{b} produced undeclared class {value!r}"
            seen.add(value)
    assert seen == declared, f"precondition: the sweep reached only {sorted(seen)}"


@pytest.mark.req("FR-02-02", "D-03")
def test_the_declared_classes_are_adr_0023s_four_in_order() -> None:
    """Both paths unpack this tuple positionally, so its order carries meaning.

    A reorder in the registry would silently swap two classes' definitions in `batch`, which no
    value test would catch because every expectation would move with it.
    """
    assert categories_for("corridor_class") == (
        "DOMESTIC",
        "INTRA_BLOC",
        "CROSS_BLOC_AFRICA",
        "INTERCONTINENTAL",
    )
    assert REGISTRY["corridor_class"].degeneracy, (
        "two of these four classes are unreachable on the generated dataset, which must stay "
        "declared (PB-43)"
    )
