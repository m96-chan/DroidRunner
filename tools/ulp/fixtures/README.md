# f32 ADD fixtures

Committed rather than generated in CI, because they are 12 KB, deterministic,
and the device job that uses them has no Python. Regenerate with:

    python tools/ulp/make-add-f32.py tools/ulp/fixtures

Every input is a multiple of 0.25 and every exact sum is representable in
binary32, so nothing here needs rounding — a deviation is the hardware's, not
the format's. `expected.bin` is the reference, computed in binary32 and shipped
with the model so a comparison cannot quietly recompute it differently.
