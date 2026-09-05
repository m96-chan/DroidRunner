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


def raw_word_difference(expected, actual):
    """Difference of the bit patterns as written, sign bit and all."""
    return actual.view(np.int32).astype(np.int64) - expected.view(np.int32).astype(np.int64)


def monotonic(values):
    """Bits reordered so that comparing them orders the floats themselves.

    binary32 is sign-and-magnitude, so the raw words only increase with value
    for positives. Folding the negatives makes one step of this key one
    representable value, whatever the sign.
    """
    bits = values.view(np.int32).astype(np.int64)
    return np.where(bits < 0, np.int64(-0x80000000) - bits, bits)


def ulp_difference(expected, actual):
    """Distance in representable values, counted toward positive."""
    return monotonic(actual) - monotonic(expected)


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

    # The two readings agree on positives and disagree on negatives, which is
    # what separates "+1 to the word" from "+1 step toward positive".
    if np.any(expected < 0):
        raw = raw_word_difference(expected, actual)
        raw_counts = {int(k): int(v) for k, v in zip(*np.unique(raw, return_counts=True))}
        print(f"raw-word difference histogram: {raw_counts}")
        negatives = expected < 0
        print(f"on the {int(negatives.sum())} negative results: "
              f"toward-positive {sorted(set(difference[negatives].tolist()))}, "
              f"raw-word {sorted(set(raw[negatives].tolist()))}")

    exact = counts.get(0, 0)
    print(f"{exact} of {expected.size} bit-exact")
    for index in np.flatnonzero(difference)[:4]:
        print(f"  [{index}] expected 0x{expected[index].view(np.uint32):08x} "
              f"device 0x{actual[index].view(np.uint32):08x} "
              f"({difference[index]:+d} ULP)")

    # Where the low mantissa bit is already 1, an increment carries and a
    # forced bit does nothing. Everything measured before this set was a
    # multiple of 0.25, so the two were indistinguishable.
    odd = (expected.view(np.uint32) & 1).astype(bool)
    if odd.any():
        raw = raw_word_difference(expected, actual)
        print(f"of the {int(odd.sum())} results whose low mantissa bit was already 1: "
              f"raw-word {sorted(set(raw[odd].tolist()))}")
        print(f"of the {int((~odd).sum())} whose low bit was 0: "
              f"raw-word {sorted(set(raw[~odd].tolist()))}")

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
