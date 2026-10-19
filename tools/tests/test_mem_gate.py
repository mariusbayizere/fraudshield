"""tools/bin/mem-gate runs a command only when there is memory for it.

The owner's floor (1.5 GB available) was once checked with `free` in front of a command that ran
anyway (M8 screen 4, 2026-10-19). These tests pin that the command is never started below the floor,
that its own exit status passes through above it, and that a missing reading refuses.
"""

from __future__ import annotations

import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "bin" / "mem-gate"


def meminfo(tmp_path: Path, available_mb: int | None) -> Path:
    path = tmp_path / "meminfo"
    lines = ["MemTotal:        7969000 kB"]
    if available_mb is not None:
        lines.append(f"MemAvailable:    {available_mb * 1024} kB")
    path.write_text("\n".join(lines) + "\n")
    return path


def run(
    tmp_path: Path,
    available_mb: int | None,
    *args: str,
    poll: str = "10",
    reading: Path | None = None,
) -> tuple[subprocess.CompletedProcess[str], Path]:
    marker = tmp_path / "ran"
    source = reading if reading is not None else meminfo(tmp_path, available_mb)
    env = {"PATH": "/usr/bin:/bin", "MEM_GATE_MEMINFO": str(source)}
    env["MEM_GATE_POLL"] = poll
    result = subprocess.run(  # noqa: S603 - the script under test, with fixed arguments
        [str(SCRIPT), *args, "--", "sh", "-c", f'touch "{marker}"; exit 3'],
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )
    return result, marker


def test_below_the_floor_the_command_never_starts(tmp_path: Path) -> None:
    result, marker = run(tmp_path, 1535)
    assert result.returncode == 75
    assert not marker.exists()
    assert "REFUSED: 1535 MB available, below the 1536 MB floor" in result.stderr


def test_at_the_floor_the_command_runs_and_its_status_passes_through(tmp_path: Path) -> None:
    result, marker = run(tmp_path, 1536)
    assert marker.exists()
    assert result.returncode == 3


def test_the_floor_can_be_raised(tmp_path: Path) -> None:
    result, marker = run(tmp_path, 1700, "--floor", "1800")
    assert result.returncode == 75
    assert not marker.exists()


def test_a_missing_reading_refuses(tmp_path: Path) -> None:
    result, marker = run(tmp_path, None)
    assert result.returncode == 75
    assert not marker.exists()


def test_waiting_still_refuses_if_memory_never_frees(tmp_path: Path) -> None:
    started = time.monotonic()
    result, marker = run(tmp_path, 1000, "--wait", "2", poll="1")
    assert result.returncode == 75
    assert not marker.exists()
    # It did wait, about as long as asked.
    assert 2 <= time.monotonic() - started < 4


def test_waiting_runs_the_command_once_memory_frees(tmp_path: Path) -> None:
    reading = meminfo(tmp_path, 1000)

    def free_up() -> None:
        time.sleep(1.5)
        reading.write_text(f"MemAvailable:    {2000 * 1024} kB\n")

    thread = threading.Thread(target=free_up)
    thread.start()
    result, marker = run(tmp_path, None, "--wait", "6", poll="1", reading=reading)
    thread.join()
    assert marker.exists()
    assert result.returncode == 3


def test_the_wait_is_not_rounded_up_to_the_poll(tmp_path: Path) -> None:
    started = time.monotonic()
    result, _ = run(tmp_path, 1000, "--wait", "1", poll="30")
    assert result.returncode == 75
    assert time.monotonic() - started < 5


def test_only_the_first_reading_counts(tmp_path: Path) -> None:
    reading = tmp_path / "doubled"
    reading.write_text("MemAvailable:    1024000 kB\nMemAvailable:    1024000 kB\n")
    result, marker = run(tmp_path, None, reading=reading)
    assert result.returncode == 75
    assert not marker.exists()


def test_numbers_are_decimal_even_with_a_leading_zero(tmp_path: Path) -> None:
    result, marker = run(tmp_path, 1000, "--floor", "0999")
    assert result.returncode == 3
    assert marker.exists()
    result, _ = run(tmp_path, 1000, "--wait", "08", poll="1")
    assert result.returncode == 75


def test_a_malformed_call_is_a_usage_error(tmp_path: Path) -> None:
    for args in (["--floor", "lots"], ["--bogus"], ["--wait", "9999999999"], ["--floor", ""]):
        result, marker = run(tmp_path, 4000, *args)
        assert result.returncode == 2, args
        assert not marker.exists()
    for poll in ("0", "x"):
        result, marker = run(tmp_path, 4000, "--wait", "5", poll=poll)
        assert result.returncode == 2, poll
        assert not marker.exists()
