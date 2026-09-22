"""The checks are scripts, not an installed package: make them importable by the tests."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
