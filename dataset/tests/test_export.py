from __future__ import annotations

import csv as csv_module
import json
import resource
import subprocess
import sys
from pathlib import Path

import pyarrow.dataset as ds
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.fingerprint import dataset_fingerprint
from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.paths import REALISM_REPORT_MD
from fraudshield_dataset.release.export import LICENCE, TABLES, export, verify

pytestmark = pytest.mark.req("ML-DATA-08")

SEED = 20260917
ROWS = 20_000


@pytest.fixture(scope="module")
def dataset(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("dataset")
    generate(
        build_config(load_parameters(), seed=SEED, total_rows=ROWS),
        output,
        allow_missing_scenarios=True,
    )
    return output


@pytest.fixture(scope="module")
def report(dataset: Path, tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A realism report that describes *this* dataset.

    Written here rather than generated: the export's contract is that the bundled report carries
    the dataset's fingerprint, and running the realism checks to produce one would test the report
    renderer instead — which `test_realism_checks` does, against a dataset large enough for the
    checks to mean something. What matters here is the fingerprint line and whether export reads
    it.

    The committed report cannot be used: it describes a million-row run, which is precisely the
    mismatch PB-41 exists to refuse.
    """
    path = tmp_path_factory.mktemp("report") / "realism_report.md"
    path.write_text(
        "# report for the exported dataset\n\n"
        f"| Dataset fingerprint SHA-256 | `{dataset_fingerprint(dataset)}` |\n",
        encoding="utf-8",
    )
    return path


@pytest.fixture(scope="module")
def release(dataset: Path, report: Path, tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("release")
    export(dataset, output / "release", report=report)
    return output / "release"


def test_csv_carries_every_parquet_row_and_column(dataset: Path, release: Path) -> None:
    for table in TABLES:
        parquet = ds.dataset(dataset / table, format="parquet", partitioning="hive").to_table()
        with (release / "csv" / f"{table}.csv").open(encoding="utf-8", newline="") as handle:
            reader = csv_module.reader(handle)
            header = next(reader)
            rows = sum(1 for _ in reader)
        # The hive partition column is a directory name, not data, so CSV carries the columns the
        # schema declares and the same number of rows.
        assert header == [name for name in parquet.schema.names if name != "month"]
        assert rows == parquet.num_rows


def test_the_release_verifies_against_its_own_checksums(release: Path) -> None:
    assert verify(release) == []
    listed = (release / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    names = {line.split("  ", 1)[1] for line in listed}
    assert LICENCE.name in names
    assert "release.json" in names
    assert any(name.startswith("parquet/transactions/month=") for name in names)
    assert "csv/transactions.csv" in names


def test_a_changed_file_is_reported_by_verify(release: Path, tmp_path: Path) -> None:
    copy = tmp_path / "tampered"
    copy.mkdir()
    for path in release.rglob("*"):
        target = copy / path.relative_to(release)
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.is_file():
            target.write_bytes(path.read_bytes())
    tampered = copy / "csv" / "labels.csv"
    tampered.write_text(tampered.read_text(encoding="utf-8").replace("true", "false", 1))
    assert verify(copy) == ["csv/labels.csv"]


def test_release_json_describes_the_data_and_its_licence(dataset: Path, release: Path) -> None:
    record = json.loads((release / "release.json").read_text(encoding="utf-8"))
    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    assert record["seed"] == SEED
    assert record["rows"] == manifest["rows"]
    assert record["rows_by_table"]["transactions"] == manifest["rows"]
    assert record["licence"]["name"] == "CC BY 4.0"
    # The licence is the file published by Creative Commons, unmodified (dataset/release/README).
    assert (
        record["licence"]["sha256"]
        == "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411"
    )
    assert record["formats"] == ["parquet", "csv"]
    # PB-41: a hash of the rows that arrived, not of the parameters that produced them.
    assert record["dataset_fingerprint_sha256"] == dataset_fingerprint(dataset)


def test_export_refuses_a_report_that_describes_other_data(dataset: Path, tmp_path: Path) -> None:
    """PB-41, at the point where it matters. A release ships the data and the report together and
    a consumer has nothing to tell them apart with, so a mismatch is refused rather than warned
    about.

    The precondition is asserted first (E12): the wrong report must genuinely not carry this
    dataset's fingerprint, or the refusal below would be about something else.
    """
    wrong = tmp_path / "wrong_report.md"
    wrong.write_text("| Dataset fingerprint SHA-256 | `" + "0" * 64 + "` |\n", encoding="utf-8")
    assert dataset_fingerprint(dataset) not in wrong.read_text(encoding="utf-8")

    with pytest.raises(ValueError, match="does not describe this dataset"):
        export(dataset, tmp_path / "refused", report=wrong)


def test_export_refuses_the_committed_report_for_another_dataset(
    dataset: Path, tmp_path: Path
) -> None:
    """The default path, checked rather than assumed.

    The committed report describes the run that last regenerated it. Exporting any other dataset
    while bundling it is the state PB-39 shipped for two commits, so it must fail by default and
    not only when someone remembers to pass `--report`.
    """
    assert REALISM_REPORT_MD.exists(), "precondition: there is a committed report to bundle"
    assert dataset_fingerprint(dataset) not in REALISM_REPORT_MD.read_text(encoding="utf-8")

    with pytest.raises(ValueError, match="does not describe this dataset"):
        export(dataset, tmp_path / "default")


def test_the_bundled_report_arrives_under_its_release_name(release: Path) -> None:
    """A report generated into a CI artifact directory must still land as `realism_report.md`."""
    assert (release / "realism_report.md").exists()
    assert "Dataset fingerprint" in (release / "realism_report.md").read_text(encoding="utf-8")


def test_export_streams_within_the_memory_budget(dataset: Path, tmp_path: Path) -> None:
    """Measured in a fresh process: ``ru_maxrss`` is a high-water mark, so a delta inside this
    process reads as zero once anything earlier in the suite has peaked (M2 principal review).
    """
    script = (
        "from pathlib import Path\n"
        "from fraudshield_dataset.fingerprint import dataset_fingerprint\n"
        "from fraudshield_dataset.release.export import export\n"
        f"source = Path({str(dataset)!r})\n"
        f"report = Path({str(tmp_path / 'memory_report.md')!r})\n"
        "report.write_text(f'`{dataset_fingerprint(source)}`\\n')\n"
        f"export(source, Path({str(tmp_path / 'streamed')!r}), report=report)\n"
    )
    finished = subprocess.run(  # noqa: S603 - fixed interpreter, no shell
        [sys.executable, "-c", script], check=True, capture_output=True, text=True
    )
    peak = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024

    assert finished.returncode == 0
    assert peak < 512 * 2**20, f"export peaked at {peak / 2**20:.0f} MiB"


def test_export_refuses_a_directory_without_a_manifest(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match=r"manifest\.json"):
        export(tmp_path, tmp_path / "out")


def test_cli_export(
    dataset: Path, report: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    argv = [
        "export",
        str(dataset),
        "--output",
        str(tmp_path / "cli"),
        "--no-csv",
        "--report",
        str(report),
    ]
    assert cli.main(argv) == 0
    assert "exported" in capsys.readouterr().out
    assert not (tmp_path / "cli" / "csv").exists()
    assert json.loads((tmp_path / "cli" / "release.json").read_text())["formats"] == ["parquet"]
