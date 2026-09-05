#!/usr/bin/env python3
"""How far the device's f32 answers are from the exact ones, in ULP.

In ULP because that is the only unit in which "how wrong is this" has a stable
answer across magnitudes: the same one-bit fault reads as 1.19e-07 near 1.0 and
3.05e-05 near 256, and an absolute bound passes one and fails the other.

The histogram is the point, not the maximum. Rounding is distributed and lands
on both sides; every result high by exactly one is a bias, and a bias
accumulates along a graph in a way rounding does not.

Usage: compare.py EXPECTED.bin ACTUAL.bin
"""
import pathlib
import sys

import numpy as np


def ulp_difference(expected, actual):
    """Signed distance in representable steps, which is subtraction on the bits.

    Monotonic for same-signed finite floats, so the integer difference of the
    bit patterns *is* the number of representable values between them.
    """
    return actual.view(np.int32).astype(np.int64) - expected.view(np.int32).astype(np.int64)


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: compare.py EXPECTED.bin ACTUAL.bin")
    expected = np.frombuffer(pathlib.Path(sys.argv[1]).read_bytes(), dtype="<f4")
    actual = np.frombuffer(pathlib.Path(sys.argv[2]).read_bytes(), dtype="<f4")
    if expected.shape != actual.shape:
        sys.exit(f"{expected.size} expected values, {actual.size} came back")

    difference = ulp_difference(expected, actual)
    counts = {int(k): int(v) for k, v in zip(*np.unique(difference, return_counts=True))}
    print(f"ULP difference histogram: {counts}")

    exact = counts.get(0, 0)
    print(f"{exact} of {expected.size} bit-exact")
    for index in np.flatnonzero(difference)[:4]:
        print(f"  [{index}] expected 0x{expected[index].view(np.uint32):08x} "
              f"device 0x{actual[index].view(np.uint32):08x} "
              f"({difference[index]:+d} ULP)")

    if exact == expected.size:
        return 0
    # Not a failure by itself: a device may legitimately differ. It is a
    # finding, and the shape of the histogram says which kind.
    off = [k for k in counts if k != 0]
    if all(k > 0 for k in off) or all(k < 0 for k in off):
        print("every deviation is in the same direction — a bias, not rounding")
    return 0


if __name__ == "__main__":
    sys.exit(main())
