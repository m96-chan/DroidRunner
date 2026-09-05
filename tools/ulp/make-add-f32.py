#!/usr/bin/env python3
"""An f32 ADD whose every exact sum is representable, for checking arithmetic.

Reported by the first outside consumer: on a Snapdragon 8 Gen 2 (Hexagon V73),
`TfLiteQnnDelegate:qnn-htp` returned 1024 of 1024 f32 ADD results high by
exactly one ULP. Uniform, not distributed — which is a bias rather than
rounding.

This is the experiment that asks whether the same holds on other silicon. The
values matter: every input is a multiple of 0.25 and every exact sum is
representable in binary32, so nothing here *needs* rounding. A deviation is
then the hardware's, not the format's.

Also: comparison is in ULP, not in absolute error. The same fault reads as
1.19e-07 near 1.0 and 3.05e-05 near 256, and an absolute bound passes one and
fails the other — which cost the reporter a run.

Usage: make-add-f32.py OUTDIR
"""
import pathlib
import struct
import sys

import numpy as np
import tensorflow as tf

COUNT = 1024


def exact_values(count):
    """Multiples of 0.25, spanning magnitudes so a relative fault is visible."""
    a = (np.arange(count) % 64) * 0.25
    b = ((np.arange(count) // 4) % 32) * 0.25
    return a.astype("float32"), b.astype("float32")


def resolution_values(count):
    """1.0 plus a step, sweeping the step from 2^-1 down to 2^-23.

    Asks where the accelerator stops resolving a difference rather than
    assuming a rule about bits. The coarse sets could not have answered it: a
    multiple of 0.25 needs seven significand bits, and everything is
    resolvable at that granularity, so a path that keeps only the top bits
    looks bit-exact except for whatever it does to the last one.

    Each step size is repeated so a single wrong value cannot be read as a
    threshold, and the exponent is the row: the finest step that still comes
    back distinct is the answer.
    """
    exponents = np.repeat(np.arange(1, 24), count // 23 + 1)[:count]
    a = np.ones(count)
    b = np.float32(2.0) ** -exponents.astype("float32")
    return a.astype("float32"), b.astype("float32")


def odd_mantissa_values(count):
    """Sums whose low mantissa bit is already 1, half the time.

    The set that separates the last two explanations. Everything run so far is
    a multiple of 0.25, so every low mantissa bit is 0 — and on such values
    "add one to the word" and "set the low bit of the word" are the same
    output. They are different faults: an increment carries and accumulates, a
    forced bit is idempotent and leaves an already-odd mantissa alone.

    `1.0 + i * 2^-23` is exactly representable for i below 2^23 and needs no
    rounding, and its low mantissa bit is `i & 1`. Signs alternate so the
    answer is not read off positives only.
    """
    steps = np.arange(count) % 512
    a = np.where(np.arange(count) % 2 == 0, 1.0, -1.0)
    b = np.where(np.arange(count) % 2 == 0, 1.0, -1.0) * steps * np.float32(2.0) ** -23
    return a.astype("float32"), b.astype("float32")


def signed_values(count):
    """The same, shifted so results land on both sides of zero.

    Suggested by NxPU, and it is the experiment that separates two explanations
    of the Qualcomm deviation. Floats are sign-and-magnitude, so incrementing
    the raw word moves a negative number *away* from zero while incrementing by
    one step *toward positive* moves it closer. Both look identical on positive
    results and opposite on negative ones, so one run tells them apart.
    """
    a = (np.arange(count) % 64) * 0.25 - 8.0
    b = ((np.arange(count) // 4) % 32) * 0.25 - 4.0
    return a.astype("float32"), b.astype("float32")


def main():
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    fn = tf.function(lambda a, b: a + b).get_concrete_function(
        tf.TensorSpec((1, COUNT), tf.float32), tf.TensorSpec((1, COUNT), tf.float32)
    )
    (out / "add-f32.tflite").write_bytes(
        tf.lite.TFLiteConverter.from_concrete_functions([fn]).convert()
    )

    for prefix, (a, b) in (
        ("", exact_values(COUNT)),
        ("signed-", signed_values(COUNT)),
        ("odd-", odd_mantissa_values(COUNT)),
        ("resolution-", resolution_values(COUNT)),
    ):
        (out / f"{prefix}input-0.bin").write_bytes(a.tobytes())
        (out / f"{prefix}input-1.bin").write_bytes(b.tobytes())
        (out / f"{prefix}expected.bin").write_bytes((a + b).tobytes())

    a, b = exact_values(COUNT)
    # The reference is computed here, in binary32, and shipped with the model:
    # the comparison job must not be free to recompute it differently.
    exact = all(
        struct.unpack("<f", struct.pack("<f", float(x)))[0] == float(x)
        for x in np.concatenate([a, b, a + b])
    )
    print(f"{COUNT} pairs, every value exactly representable: {exact}", file=sys.stderr)
    if not exact:
        sys.exit("a value was not representable — the experiment would be meaningless")


if __name__ == "__main__":
    main()
