#!/usr/bin/env python3
"""Writes the index of committed matrices — issue #217.

`docs/matrices/README.md` lists one row per phone, and that row was kept by
hand. It drifted: the nubia's `gpu` column — the accelerator every phone has,
and the reason #140 exists — was measured and committed, then left out of the
index, which read as though nobody had asked that silicon for it.

The matrices are generated, so the table listing them is generated too. Every
cell is read out of the committed JSON: the SoC exactly as the phone reported
it, and the drivers in the order the matrix carries them, which is the order of
that matrix's own columns.

Usage: index.py [--check] [--matrices DIR] [--readme FILE]

Exit status:
  0  the README holds the table the matrices imply, or it has just been written
  1  --check, and it does not
"""
import argparse
import difflib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# The first of these is also how the table is found again: the generated rows
# replace the run of table lines beginning with it, and nothing else.
HEADER = ["| file | SoC | drivers |", "| --- | --- | --- |"]


def row(path):
    """One phone, as the line that stands for it in the index."""
    matrix = json.loads(path.read_text())
    device = matrix.get("device", {})
    drivers = ", ".join(f"`{driver}`" for driver in matrix.get("drivers", []))
    # A matrix naming no driver measured nothing, and an empty cell in a table
    # of what silicon does is the one thing a reader will fill in themselves.
    return f"| `{path.name}` | {device.get('soc') or '—'} | {drivers or '—'} |"


def table(matrices):
    paths = sorted(matrices.glob("*.json"))
    if not paths:
        sys.exit(f"no matrices in {matrices}")
    return HEADER + [row(path) for path in paths]


def splice(text, rows):
    """The README with its device table replaced by `rows`."""
    lines = text.split("\n")
    start = next((i for i, line in enumerate(lines)
                  if line.startswith(HEADER[0])), None)
    if start is None:
        sys.exit(f"no table starting with {HEADER[0]!r} to replace")
    end = start
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    return "\n".join(lines[:start] + rows + lines[end:])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="fail if the committed README is not this table")
    parser.add_argument("--matrices", default=ROOT / "docs" / "matrices",
                        type=pathlib.Path)
    parser.add_argument("--readme", type=pathlib.Path)
    args = parser.parse_args()

    readme = args.readme or args.matrices / "README.md"
    try:
        current = readme.read_text()
    except OSError as failure:
        sys.exit(f"cannot read {readme}: {failure}")

    wanted = splice(current, table(args.matrices))

    if not args.check:
        if wanted != current:
            readme.write_text(wanted)
            print(f"rewrote the device table in {readme}", file=sys.stderr)
        else:
            print(f"{readme} already matches the matrices", file=sys.stderr)
        return

    if wanted != current:
        print("\n".join(difflib.unified_diff(
            current.split("\n"), wanted.split("\n"),
            fromfile=f"{readme} (committed)",
            tofile=f"{readme} (from the matrices)", lineterm="")),
            file=sys.stderr)
        sys.exit(f"{readme} does not list what the committed matrices hold. "
                 "Run tools/op-matrix/index.py and commit the result.")
    print(f"{readme} matches the committed matrices", file=sys.stderr)


if __name__ == "__main__":
    main()
