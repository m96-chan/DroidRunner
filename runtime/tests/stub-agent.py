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
  RESPONSE_DIR/status             HTTP status for POSTs, default 200
  RESPONSE_DIR/get-status         HTTP status for GETs, default 200
  RESPONSE_DIR/last-request.json  written with the body of the last POST

A GET always answered 200, which is why an authentication failure on one went
unnoticed for as long as it did (#205). Ask for a status and the body is the
envelope the real agent sends with it, not the capabilities payload — a 401 is
a sentence about the token, and the point of the test is that the wrapper must
not read it as a list of accelerators.
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


# What DeviceAgentServer answers when it will not route a request: the code is
# invalid-request for all three, and the HTTP status is what tells a token that
# rotated apart from a URL nobody serves.
REFUSALS = {
    401: "missing or invalid capability token",
    403: "device agent is only available while a job is running",
    404: "unknown endpoint",
    # Refused before the request was read, so nothing was attempted and the
    # same request works later. Its own code for that reason (#233).
    503: (
        "the device agent is busy: every worker and every queued slot is taken. "
        "Nothing was attempted; try the same request again in 30 seconds"
    ),
}

# The code that goes with each refusal. `invalid-request` for the three that
# are about the token or the URL; `busy` is about the phone.
REFUSAL_CODES = {503: "busy"}


def asked_status(name):
    """The HTTP status a test asked for, or 200."""
    path = DIR / name
    return int(path.read_text().strip()) if path.exists() else 200


def refusal(status):
    return json.dumps(
        {
            "schema": 1,
            "ok": False,
            "code": REFUSAL_CODES.get(status, "invalid-request"),
            "error": REFUSALS.get(status, "refused"),
        },
        separators=(",", ":"),
    )


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, body, status=200):
        payload = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        status = asked_status("get-status")
        if status != 200:
            self._send(refusal(status), status)
            return
        self._send(compact(DIR / "capabilities.json", '{"nnapi":{"devices":[]}}'))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        (DIR / "last-request.json").write_bytes(self.rfile.read(length))
        status = asked_status("status")
        # A 503 is refused before the body is looked at, so it carries the
        # agent's own envelope rather than whatever response.json holds.
        if status == 503:
            self._send(refusal(status), status)
            return
        self._send(compact(DIR / "response.json", '{"schema":1,"ok":true}'), status)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
