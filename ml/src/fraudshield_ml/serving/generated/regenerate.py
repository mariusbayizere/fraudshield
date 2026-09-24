"""Compile `contracts/proto` into this package (`scoring_pb2.py` and its `.pyi`).

Needs `grpcio-tools`, which the contracts package depends on and the scorer does not: generation
is a development step, the generated module is what ships.
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

PROTO = Path("fraudshield/scoring/v1/scoring.proto")
HERE = Path(__file__).parent
#: Prepended to each generated file so the repository's formatter and linter leave it byte-for-byte
#: as protoc wrote it; the freshness test compares everything after this header.
#: protobuf ships no type information, so strict mypy rejects the generated stub's own base classes;
#: the stub's field types still reach every caller.
HEADER = (
    "# ruff: noqa\n"
    "# fmt: off\n"
    "# mypy: ignore-errors\n"
    "# Generated from contracts/proto by regenerate.py. Do not edit.\n"
)
OUTPUTS = ("scoring_pb2.py", "scoring_pb2.pyi")


def compile_to(contracts_proto: Path, out: Path) -> dict[str, str]:
    """Compile the contract into `out` and return each output's text, header included."""
    import grpc_tools  # noqa: PLC0415 - development-only dependency
    from grpc_tools import protoc  # noqa: PLC0415

    well_known = Path(grpc_tools.__file__).parent / "_proto"
    with tempfile.TemporaryDirectory() as scratch:
        status = protoc.main(
            [
                "grpc_tools.protoc",
                f"-I{contracts_proto}",
                f"-I{well_known}",
                f"--python_out={scratch}",
                f"--pyi_out={scratch}",
                str(contracts_proto / PROTO),
            ]
        )
        if status != 0:
            raise RuntimeError(f"protoc exited {status} compiling {PROTO}")
        compiled = Path(scratch) / PROTO.parent
        texts = {name: HEADER + (compiled / name).read_text() for name in OUTPUTS}
    out.mkdir(parents=True, exist_ok=True)
    for name, text in texts.items():
        (out / name).write_text(text)
    return texts


def main() -> int:
    root = HERE.parents[4]
    compile_to(root / "contracts" / "proto", HERE)
    print(f"regenerated {', '.join(OUTPUTS)} in {HERE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
