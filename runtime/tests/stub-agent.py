#!/usr/bin/env python3
"""A Device Agent that answers whatever the test tells it to (issue #125).

Two things about it are not incidental:

Responses are read from a file on every request, so a test changes what the
agent says by writing that file rather than by restarting anything.

It emits compact JSON, because the real agent is `org.json` and emits no
spaces — and the wrapper reads device names with `grep -o '"name":"[^"]*"'`,
which a pretty-printer silently defeats. A stub that formatted its output
nicely would pass tests the real agent fails.

Usage: stub-agent.py PORT RESPONSE_DIR
  RESPONSE_DIR/capabilities.json  answered to GET  /v1/capabilities
  RESPONSE_DIR/response.json      answered to every POST
  RESPONSE_DIR/status            HTTP status for POSTs, default 200
  RESPONSE_DIR/last-request.json  written with the body of the last POST
"""
import http.server
import json
import pathlib
import sys

PORT = int(sys.argv[1])
DIR = pathlib.Path(sys.argv[2])


def compact(path, fallback):
    """Whatever the test asked for, re-emitted the way the real agent would."""
    if not path.exists():
        return fallback
    text = path.read_text().strip()
    try:
        return json.dumps(json.loads(text), separators=(",", ":"))
    except json.JSONDecodeError:
        # A test may want to send something that is not JSON at all.
        return text


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, body, status=200):
        payload = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._send(compact(DIR / "capabilities.json", '{"nnapi":{"devices":[]}}'))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        (DIR / "last-request.json").write_bytes(self.rfile.read(length))
        status_file = DIR / "status"
        status = int(status_file.read_text().strip()) if status_file.exists() else 200
        self._send(compact(DIR / "response.json", '{"schema":1,"ok":true}'), status)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
