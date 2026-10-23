"""Name every raw-topic record a step 2 case produced, by its ``transaction_id`` (PB-198).

Step 2 used to compare a count of accepted ingests with a count of records on
``fs.transactions.raw``, so an extra record could not say what it was. This reads the ids the
harness wrote (``--fs-ids-out``: sent, accepted, replayed) and the case's records (one line per
record, ``partition<TAB>offset<TAB>key<TAB>value``, the key being the ``transaction_id``) and
classes each record:

* ``accepted``: the first record of an id the harness counted as accepted;
* ``late``: the first record of an id the harness sent but did not count as accepted: for
  example a request in flight when the run stopped, or one the API published but the harness
  counted as failed (a contract check, a client timeout);
* ``duplicate``: a second or later record of the same id; reported with it are whether the
  envelope's ``event_id`` repeats (a re-publish that consumers deduplicate) or differs, and
  whether the harness replayed that id;
* ``foreign``: an id the harness never sent in this case.

It also lists the accepted ids with no record. It reports and exits 0 whatever it finds: the case's
verdict is unchanged until a run has shown what the extra records are.

Usage: reconcile.py <ids.json> <records.tsv> <out.json>; prints a one-line summary.
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


def event_id(value: str) -> str | None:
    """The envelope's ``event_id``, or None when the value is not a JSON object carrying one."""
    try:
        envelope = json.loads(value)
    except ValueError:
        return None
    found = envelope.get("event_id") if isinstance(envelope, dict) else None
    return found if isinstance(found, str) else None


def reconcile(ids: dict[str, list[str]], lines: list[str]) -> dict[str, Any]:
    sent, accepted, replayed = set(ids["sent"]), set(ids["accepted"]), set(ids["replayed"])
    seen: dict[str, list[dict[str, Any]]] = {}
    classes: Counter[str] = Counter()
    extra: list[dict[str, Any]] = []
    for line in lines:
        partition, offset, key, value = [*line.rstrip("\n").split("\t", 3), "", "", ""][:4]
        record: dict[str, Any] = {
            "partition": partition,
            "offset": offset,
            "id": key,
            "event_id": event_id(value),
        }
        earlier = seen.setdefault(key, [])
        if earlier:
            kind = "duplicate"
            ids_known = record["event_id"] is not None and all(r["event_id"] for r in earlier)
            record["event_id_repeats"] = (
                record["event_id"] in {r["event_id"] for r in earlier} if ids_known else None
            )
            record["replayed"] = key in replayed
        elif key in accepted:
            kind = "accepted"
        elif key in sent:
            kind = "late"
        else:
            kind = "foreign"
        earlier.append(record)
        classes[kind] += 1
        if kind != "accepted":
            extra.append(dict(record, kind=kind))
    return {
        "records": len(lines),
        "classes": dict(sorted(classes.items())),
        "extra": extra,
        "accepted_without_record": sorted(accepted - seen.keys()),
        "sent": len(ids["sent"]),
        "accepted": len(ids["accepted"]),
        "replayed": len(ids["replayed"]),
    }


def detail(record: dict[str, Any]) -> str:
    """For a duplicate, whether its event_id repeats and whether the harness replayed the id."""
    if record["kind"] != "duplicate":
        return ""
    repeats = {True: "event_id repeats", False: "new event_id", None: "event_id unknown"}
    replayed = "replayed" if record["replayed"] else "not replayed"
    return f" ({repeats[record['event_id_repeats']]}, {replayed})"


def summary(result: dict[str, Any]) -> str:
    """One line for the case's row: the classes, then each extra record by id and offset."""
    parts = [f"{kind} {count}" for kind, count in result["classes"].items()] or ["no records"]
    if result["accepted_without_record"]:
        parts.append(f"accepted without a record {len(result['accepted_without_record'])}")
    named = [
        f"{r['kind']} {r['id']} at {r['partition']}:{r['offset']}{detail(r)}"
        for r in result["extra"]
    ]
    if len(named) > 5:
        named = [*named[:5], f"{len(named) - 5} more in the case's reconcile.json"]
    return "records: " + ", ".join(parts) + ("; " + "; ".join(named) if named else "")


def main() -> None:
    ids_path, records_path, out_path = (Path(arg) for arg in sys.argv[1:4])
    ids = json.loads(ids_path.read_text())
    lines = [line for line in records_path.read_text().splitlines() if line.strip()]
    result = reconcile(ids, lines)
    out_path.write_text(json.dumps(result, indent=2))
    print(summary(result))


if __name__ == "__main__":
    main()
