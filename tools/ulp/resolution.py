#!/usr/bin/env python3
"""Where an accelerator stops resolving a difference (issue: f32 arithmetic).

Reads the `resolution-` fixture set back and reports, per step size, whether
the device distinguished `1.0 + 2^-k` from `1.0`. That turns "it loses
precision" into "it carries about N significand bits", which is a number
somebody can act on.
"""
import pathlib
import sys

import numpy as np


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: resolution.py INPUT-1.bin EXPECTED.bin ACTUAL.bin")
    step = np.frombuffer(pathlib.Path(sys.argv[1]).read_bytes(), dtype="<f4")
    expected = np.frombuffer(pathlib.Path(sys.argv[2]).read_bytes(), dtype="<f4")
    actual = np.frombuffer(pathlib.Path(sys.argv[3]).read_bytes(), dtype="<f4")

    finest = None
    for exponent in range(1, 24):
        rows = np.flatnonzero(step == np.float32(2.0) ** -exponent)
        if rows.size == 0:
            continue
        got = actual[rows]
        want = expected[rows]
        distinct = not np.all(got == np.float32(1.0))
        exact = bool(np.all(got.view(np.uint32) == want.view(np.uint32)))
        note = "exact" if exact else ("resolved, not exact" if distinct else "LOST — returned 1.0")
        print(f"  1.0 + 2^-{exponent:<2}  {note}"
              f"   device 0x{got[0].view(np.uint32):08x}  want 0x{want[0].view(np.uint32):08x}")
        if distinct:
            finest = exponent

    if finest is not None:
        print(f"\nfinest step still resolved: 2^-{finest} "
              f"— about {finest + 1} significand bits, against binary32's 24")
    return 0


if __name__ == "__main__":
    sys.exit(main())
