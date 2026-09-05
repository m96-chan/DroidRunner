# Does the accelerator compute what it was asked to?

Nothing in this project asked that until now. The NPU verification counts
delegated nodes and records a median; the differential test compares a top-1
*class* on an int8 network, which is an argmax and survives every result being
wrong by a bit. `executed: accelerator` has always said **who** executed a
graph and never what arithmetic they used.

## What was measured

Prompted by NxPU, who found f32 `ADD` results deviating on a Snapdragon 8 Gen 2.
On the **SM8650 (Hexagon V75), through `TfLiteQnnDelegate:qnn-htp`**, one
expression predicts every word we have measured:

```
fp16(a) + fp16(b)  ->  back to f32  ->  low bit of the word set, unless the result is zero
```

| fixture set | what it was for | words predicted |
| --- | --- | --- |
| `input-*` | multiples of 0.25 | 1024 / 1024 |
| `signed-*` | results either side of zero | 1024 / 1024 |
| `odd-*` | sums with an odd low mantissa bit | 1024 / 1024 |
| `resolution-*` | `1.0 + 2^-k`, k from 1 to 23 | 1024 / 1024 |

**4096 of 4096, bit for bit.** Reimplemented independently by NxPU against the
same words, which is why it is stated as a rule rather than as a curve fit.

The resolution sweep is where it becomes legible:

```
2^-1  … 2^-10   device = exact + 1      the addend is resolved
2^-11 … 2^-23   device = 0x3f800001     the addend is lost, and still +1
```

The cliff is fp16's ULP at 1.0, and 2^-11 falls on the far side of it for the
right reason: it is a half-ULP tie, and 1.0 has the even significand.

## What is claimed, and what is not

Claimed: on that phone, through that delegate build, an f32 `ADD` behaves as
above. **Not** claimed: other operators, other shapes, other delegate builds,
Hexagon V73, or that this is documented Qualcomm behaviour rather than a defect.

MediaTek's `mtk-dsp_shim` through NNAPI is **bit-exact on every one of these
sets**, and so is the CPU. So this is the Qualcomm path and not accelerators in
general — that distinction took a second vendor to establish, and it is the
kind of thing only a fleet you keep can answer.

## Two traps, both paid for

**Coarse fixtures hide precision.** Every value in the first two sets is a
multiple of 0.25 — seven significand bits — and everything is resolvable at
that granularity, so a path keeping only the top bits looks exact apart from
its last bit. It produced a confident, wrong rule ("the mantissa is incremented
by one"), and only operands needing the full significand exposed it.

**`+1` and `set the low bit` cannot be told apart here, ever.** An f32
converted from fp16 has its low 13 mantissa bits zero, so the value before the
change is always even:

```
fp16-derived f32 words with the low bit already set: 0 of 1024
```

That is structural, not a gap in the fixtures. Hence "the low bit ends up set"
rather than "incremented" — a set built to separate them could not have worked.

## Running it

The fixtures are committed, because they are 12 KB, deterministic, and the
device job has no Python. `Device model benchmark` runs all four sets against
the CPU and the accelerator; the comparison happens off-device, because ULP
arithmetic in awk is not something to trust a finding to.

```sh
python tools/ulp/make-add-f32.py tools/ulp/fixtures   # regenerate
python tools/ulp/compare.py    EXPECTED.bin ACTUAL.bin
python tools/ulp/resolution.py resolution-input-1.bin resolution-expected.bin ACTUAL.bin
```

**The CPU control must be bit-exact before any of it means anything.** A
reference the CPU disagrees with says nothing about an accelerator — the same
reasoning as the operator matrix's control column.
