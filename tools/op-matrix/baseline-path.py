#!/usr/bin/env python3
"""Where the committed matrix for a given phone lives (issue #126).

Its own script rather than a heredoc inside the workflow: a shell step that
embeds Python inside YAML has bitten this repository before, and the naming has
to agree between the job that checks a matrix and the person committing one.

Named by manufacturer and model, matching how compare.py decides two matrices
are the same phone. Not by SoC — a matrix taken before #118 does not carry one.
"""
import json
import pathlib
import re
import sys


def slug(value):
    return re.sub(r"[^a-z0-9]+", "-", (value or "unknown").lower()).strip("-")


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: baseline-path.py MATRIX.json")
    device = json.loads(pathlib.Path(sys.argv[1]).read_text()).get("device", {})
    print(f"docs/matrices/{slug(device.get('manufacturer'))}-"
          f"{slug(device.get('model'))}.json")


if __name__ == "__main__":
    main()
