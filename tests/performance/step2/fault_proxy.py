"""A forwarding proxy that injects one fault in front of the real API, for step 2's refusal cases.

Step 2 of the M10 plan proves that the load harness can fail. Some of the faults it must refuse
cannot be produced by the Compose stack's API, which is correctly configured: it always reports
``synthetic_data: true`` and never revokes the demo key. This proxy forwards every request to the
real API unchanged except for the one fault it is told to inject:

* ``--environment-body`` / ``--environment-status``: answer `GET /api/v1/environment` itself;
* ``--revoke-after N``: forward the first N requests to an ingest path (the harness's preflight
  key check is the first of them), then answer 401 to every request.

Because everything else reaches the real API, a harness that ignored the fault would send real
load, and the ingest count written to ``--counts`` (and the Kafka topic the API publishes to) would
show it. Not a test double of the API, and never used for a measurement.

Usage: fault_proxy.py --listen 18081 --upstream http://127.0.0.1:8080 --counts counts.json [fault]
"""

from __future__ import annotations

import argparse
import json
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ENVIRONMENT_PATH = "/api/v1/environment"
INGEST_PREFIX = "/api/v1/transactions/ingest"
#: Headers that describe one connection, not the message, and must not be forwarded.
HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "content-length", "host"}


class Counts:
    """What the proxy forwarded, written to a file once listening and after every request, so a
    killed proxy still leaves its record."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.requests = 0
        self.ingest_forwarded = 0
        self.refused = 0

    def write(self) -> None:
        self.path.write_text(
            json.dumps(
                {
                    "requests": self.requests,
                    "ingest_forwarded": self.ingest_forwarded,
                    "refused": self.refused,
                }
            )
        )


def handler_for(args: argparse.Namespace, counts: Counts) -> type[BaseHTTPRequestHandler]:
    """The request handler, closed over the fault and the counters."""

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *items: object) -> None:  # noqa: A002
            """Quiet: the counts file is the record."""

        def answer(self, status: int, body: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def handle_any(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else None
            is_ingest = self.path.startswith(INGEST_PREFIX)
            with counts.lock:
                counts.requests += 1
                # Once revoked, every authenticated request is refused; the public environment
                # endpoint stays reachable, as it would for a real revoked key.
                revoked = (
                    args.revoke_after is not None
                    and self.path != ENVIRONMENT_PATH
                    and counts.ingest_forwarded >= args.revoke_after
                )
                if revoked:
                    counts.refused += 1
                elif is_ingest:
                    counts.ingest_forwarded += 1
                counts.write()
            if self.path == ENVIRONMENT_PATH and (
                args.environment_body is not None or args.environment_status is not None
            ):
                status = args.environment_status or 200
                self.answer(status, (args.environment_body or "").encode())
                return
            if revoked:
                self.answer(401, b'{"title":"Unauthorized","status":401}')
                return
            self.forward(body)

        def forward(self, body: bytes | None) -> None:
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
            request = urllib.request.Request(  # noqa: S310 - the upstream is a CLI argument
                args.upstream + self.path, data=body, headers=headers, method=self.command
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
                    status, reply = response.status, response.read()
                    reply_headers = response.headers
            except urllib.error.HTTPError as error:
                status, reply, reply_headers = error.code, error.read(), error.headers
            self.send_response(status)
            for key, value in reply_headers.items():
                if key.lower() not in HOP_BY_HOP:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        do_GET = handle_any  # noqa: N815
        do_POST = handle_any  # noqa: N815

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--listen", type=int, required=True)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--counts", type=Path, required=True)
    parser.add_argument("--environment-body")
    parser.add_argument("--environment-status", type=int)
    parser.add_argument("--revoke-after", type=int)
    args = parser.parse_args()
    args.upstream = args.upstream.rstrip("/")
    counts = Counts(args.counts)
    server = ThreadingHTTPServer(("127.0.0.1", args.listen), handler_for(args, counts))
    # Written only once the port is bound: the caller takes the file as the sign it listens.
    counts.write()
    server.serve_forever()


if __name__ == "__main__":
    main()
