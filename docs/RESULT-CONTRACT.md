# Device result contract

**Schema 1.**

What another repository may rely on when it points a workflow at a DroidRunner
phone. Everything here is stable within a schema version; the number changes
only for something a consumer pinned to the old one could not survive. Adding a
field is not one of those.

## The ten lines it exists for

```yaml
jobs:
  verify:
    runs-on: [self-hosted, android, npu-qnn]
    steps:
      - uses: actions/checkout@v4
      - uses: m96-chan/DroidRunner/actions/run-model@main
        id: device
        with:
          model: build/model.tflite
          device: qnn-htp
          inputs: fixtures/input-0.bin
          output-dir: out
      - run: test "${{ steps.device.outputs.executed }}" = accelerator
```

Nothing in that job needs to know a shell wrapper and a loopback HTTP API exist
underneath.

## Every response

| Field | Meaning |
| --- | --- |
| `schema` | This document's version. Always present. |
| `ok` | Whether a measurement was produced. |
| `code` | Present when `ok` is false. From the closed set below. |
| `error` | Prose, for a person. **Reworded without a schema bump — do not match on it.** |
| `message` | What the layer below actually said, verbatim. Absent when nothing below was asked. |
| `at` | Where it was thrown, when we know. For a bug report, not for a program. |

**Two fields, two owners.** `error` is ours: a sentence about what went wrong,
reworded whenever a better one exists. `message` is theirs, unedited — the
exception's own words, the vendor's own words, whatever the layer below said.
It is never summarised and never folded into `error`, because it is the half
that names the three bad tensors, and the consumer who received it said that is
what turned an afternoon into a minute.

**The same rule for every status.** There is no per-code mapping to look up:
`error` is always the sentence and `message` is always the raw text underneath,
whether the code is `refused`, `invalid-model`, `unknown-device` or anything
else. A consumer reported branching per status because they assumed one
existed ([#158](https://github.com/m96-chan/DroidRunner/issues/158)) — show
`error`, and keep `message` for whoever has to reproduce it.

**Absent, not empty**, when there is nothing. An empty string reads as *they
said nothing quotable*; absence reads as *nobody below us was asked*, which is
what is true when a request never reached a layer of its own.

Until #138 this was three shapes. The NNAPI path put the exception's class name
in `error` and its text in `message`; the Qualcomm path glued both into `error`
and had no `message`; and a third field, `detail`, held our **loader's** last
error under a name that read as the vendor's — empty for most failures,
including the ones where Qualcomm's runtime had plenty to say, because those
words arrive through TFLite's exception and never touch our loader. `detail` is
gone.

## Codes

A sweep is largely *made of* refusals, and each one is data. It is a different
thing from the phone having gone away, and the two must not be told apart by
reading English.

| Code | Meaning | Wrapper exit |
| --- | --- | --- |
| — | it ran | `0` |
| `refused` | it ran, and nothing could be attributed to the accelerator asked for | `2` |
| `invalid-model` | no interpreter could be built from the file, with or without a delegate | `1` |
| `unknown-device` | no such accelerator on this phone | `3` |
| `not-installed` | the vendor runtime this device would need is not installed | `3` |
| `invalid-request` | malformed, a path outside the job's home, **or inputs that do not fit the model** | `1` |
| `failed` | anything else that stopped a run | `1` |
| — | the agent did not answer | `4` |

`4` is the one worth stopping a sweep for. So, differently, is `invalid-model`:
a refusal is a row of data and the sweep carries on, while a file nothing can
load will fail every remaining row identically, and the fault is the caller's.
It is told apart from `failed` by loading the model again with **nothing
attached** — the same control the operator matrix runs against the CPU — so it
is a statement about the file and not about any driver.

`invalid-request` covers the other half of the same idea. Supplying an input
file that is not the size the model's tensor declares is the caller's mistake in
the same way a broken model is, and it used to arrive as `failed` — the bucket
that means *something went wrong in here*. The message names the tensor, its
dtype, its extent, the bytes wanted and the bytes given:

```
input 0 a (FLOAT32 1, 4 bytes) needs 4 bytes, but input-0.bin is 4096
```

Reported by the first consumer outside this project, whose model was rejected
before any delegate saw it and arrived as a bare `failed`:

```
Cannot create interpreter: BytesRequired number of bytes overflowed.
Tensor 0 is invalidly specified in schema.
```

`message` carries that text in full, naming each tensor, on every path. It is
deliberately not summarised — it is what turns an afternoon into a minute.

The wrapper raises `invalid-request` itself, before anything is sent, for an
option value it cannot put in a request: `--iterations`, `--size`, `--channels`,
`--filters` and `--budget-ms` must match `^[0-9]+$`, and `--device` and
`--feature` must match `^[A-Za-z0-9._+-]+$`, which is every value this document
describes. The body is built by concatenation, so a value carrying a comma or a
quote used to write fields of its own — `--iterations '1,"device":"qnn-htp"'`
moved a benchmark onto the Hexagon while the caller read the numbers as the
default driver's ([#206](https://github.com/m96-chan/DroidRunner/issues/206)).
The message names the option, and the exit status is `1`, as the table says.

**An HTTP status the agent declines a request with is reported, never returned.**
`401` and `403` are about the capability token and `404` is about the URL: none
of them has a result in it. The wrapper prints the status and the body on stderr
and exits non-zero — `1` for the `invalid-request` those envelopes carry. It
used to hand the envelope back as the payload, and since `capabilities` and
`devices` read a payload by its shape, `devices` answered
`{"schema":1,"ok":true,"devices":[]}` and exited `0`: a statement about
somebody's silicon, published from a token that had rotated
([#205](https://github.com/m96-chan/DroidRunner/issues/205)). A `400` from a
POST is the other case and still comes back to the caller, because that body is
this contract's own `code` and `message` about the request that was sent, and
`--output` is where a consumer reads it.

Each of these is checked by `runtime/tests/test-droidrunner-device.sh`, against
a stub agent on loopback, so the table is a promise with something behind it
rather than a description.

## A model result

```json
{
  "schema": 1,
  "ok": true,
  "model": "int8.tflite",
  "sizeBytes": 5434517,
  "requestedDevice": "qnn-htp",
  "executed": "accelerator",
  "executedBy": "TfLiteQnnDelegate:qnn-htp",
  "delegation": {
    "delegated": 64, "total": 64, "partitions": 1,
    "delegate": "TfLiteQnnDelegate",
    "describe": "all 64 operators on the Hexagon, 1 partitions",
    "partial": false
  },
  "iterations": 30,
  "avgUs": 1369.02, "medianUs": 1224.58, "minUs": 976.66, "maxUs": 2408.85,
  "p90Us": 1901.44, "p99Us": 2408.85,
  "conditions": {
    "stable": true,
    "before": {"thermalStatus": 0, "thermalHeadroom": 0.42,
               "batteryTemperatureC": 31.5, "charging": true, "screenOn": false},
    "after":  {"thermalStatus": 0, "…": "the same fields"}
  },
  "inputs":  [{"index": 0, "name": "images", "type": "UINT8",
               "shape": [1,224,224,3], "bytes": 150528,
               "quantizationParams": {"scale": 0.0125, "zeroPoint": 131}}],
  "outputs": [{"index": 0, "name": "Softmax", "type": "UINT8",
               "shape": [1,1000], "bytes": 1000}],
  "outputFiles": [{"index": 0, "path": "/home/runner/_work/…/out/output-0.bin",
                   "bytes": 1000, "…": "the same fields as outputs"}]
}
```

### `executed` — the field most consumers branch on

| Value | Meaning |
| --- | --- |
| `accelerator` | every operator went to the delegate |
| `partial` | some did; the rest ran on the CPU, and the split is in `delegation` |
| `cpu-fallback` | the delegate took nothing |
| `cpu` | no device was requested |
| `unknown` | a device was requested and **the delegate did not say what it took** |

**Present on every path from v0.8.0.** It arrived for NNAPI and the GPU in
v0.7.0 and for Qualcomm's own runtime in v0.8.0, so a result from anything
older can be missing it on one path and not the other. Consumers have been
reconstructing it from `delegation` to cover that
([#158](https://github.com/m96-chan/DroidRunner/issues/158)), and each writes
the shim slightly differently.

`capabilities.appVersion` says which build answered. Require **0.8.0 or later**
and the shim can go — a missing `executed` from such a build is a defect to
report, not a version to work around.

Device names a job may ask for: an NNAPI driver as `capabilities` lists it,
`qnn-htp` or `qnn-gpu` for Qualcomm's own runtime, and **`gpu`** for TFLite's
GPU delegate — the one accelerator present on every phone, and not an NNAPI
driver.

That sentence used to be the only place the full set appeared, and prose is a
poor thing to enumerate from: a consumer building the list from `devices` got
the NNAPI drivers and missed the GPU, which on the phone that reported it
accepted more operators than the NPU did ([#158](https://github.com/m96-chan/DroidRunner/issues/158)).
So `capabilities` carries **`accepts`**, a flat array of every value `--device`
takes on that phone, and `droidrunner-device devices --all` prints it.

`accepts` is what the agent will actually honour, not what the hardware might
manage: `qnn-*` appears only once the Qualcomm runtime is installed, because a
name that answers `not-installed` is worse than a name that is absent. `gpu` is
always there — the delegate ships inside the APK.

**Do not enumerate from `capabilities.gpu.allowlisted`.** It is TFLite's bundled
compatibility table and it answers `false` on an SM8650 whose Adreno runs
graphs; nothing here gates on it, and neither should you.

### Naming two accelerators (experimental)

`--device` names one. A partitioned run — some nodes on one engine, the rest on
another — has no way to be asked for, so the cost of crossing between two
engines cannot be measured at all ([#159](https://github.com/m96-chan/DroidRunner/issues/159)).

Joining names with `+` asks for that, and **only when the caller opts in**:

```
droidrunner-device test conv --device 'mtk-neuron_shim+gpu' --feature multi-delegate
```

Without `--feature multi-delegate` the joined form is not a device name, and
comes back `unknown-device` — the code a caller already branches on. Nothing
that does not ask for it behaves differently in any way.

**Order is meaningful and is not normalised.** TFLite offers each delegate what
the previous one did not claim, so the first name gets the graph first.

The result then carries **`delegations`**, one entry per delegate that claimed
anything, in the order the claims were made:

```json
"delegations": [
  {"delegate": "TfLiteNnapiDelegate",  "delegated": 3, "total": 7, "partitions": 2},
  {"delegate": "TfLiteGpuDelegateV2",  "delegated": 4, "total": 7, "partitions": 1}
]
```

`delegation` is unchanged and still answers *what happened to this graph*. It
reports the last claim, which is the whole story while one delegate is attached
and one delegate's share when two are — which is why the array exists rather
than the field changing shape underneath the callers that read it.

**`qnn-*` cannot appear in the list**, and is refused with that reason rather
than a generic one. Qualcomm's runtime is in a separate process, two delegates
need one address space, and the process split is there because the FSF's line
for "one program" is the shared address space and PRoot's GPL-2.0 code is in
the main process ([#82](https://github.com/m96-chan/DroidRunner/issues/82)). On
that vendor a crossing is IPC and no interpreter spans both engines — which is
a fact about the device, not a gap waiting to be filled.

Experimental means the spelling may change. The refusal without the flag will
not.

`devices` itself still lists the NNAPI drivers and only those. It has always
meant that, and a consumer parsing it should not have the meaning change
underneath them.

**`accelerator` is a statement about who executed the graph, not about the
arithmetic they used.** On the SM8650's Hexagon, through `qnn-htp`, an f32
`ADD` is computed in fp16 and returned with the low bit of the word set — 4096
of 4096 measured results predicted by that one rule, while MediaTek and the CPU
are bit-exact on the same fixtures. A result can be honestly attributed and
still not be the number binary32 would give. See
[`tools/ulp/README.md`](../tools/ulp/README.md).

`executedBy` names **both halves** — the delegate that claimed the nodes and the
driver it was pinned to, e.g. `TfLiteNnapiDelegate:nnapi-reference`. That
example is why: `accelerator` alone reads identically for a graph NNAPI handed
to its CPU reference.

### Which CPU, when it was the CPU

`cpu` and `cpu-fallback` each cover more than one path, and the paths are an
order of magnitude apart. On the same phone and network:

| `executedBy` | what it is | median |
| --- | --- | --- |
| `TfLiteXNNPackDelegate` | an optimised, multi-threaded CPU kernel | 26,379 µs |
| `TfLiteNnapiDelegate:nnapi-reference` | NNAPI's reference implementation, which exists to be correct rather than fast | 257,104 µs |

So **`executedBy` is what says which**, and a ratio computed against the wrong
one says something untrue about the accelerator it is compared with. Reported by
the NxPU side, who spotted that the two CPU numbers in a MediaTek table looked
like a contradiction and are not one.

`unknown` deserves a word, because it is the failure mode of how this is
measured. The split is read out of a line TFLite prints while applying a
delegate — there is no API for it in any version we have looked at — so a
TFLite that rewords that line produces `unknown` for everything. **Treat
`unknown` as not-accelerated.** `tools/check-tflite-wording.sh` runs on every
build and fails when the wording it depends on leaves the library, so this
should never reach you silently ([#128](https://github.com/m96-chan/DroidRunner/issues/128)).

There is no field naming the operators a driver refused. TFLite does not print
them — neither does Qualcomm's delegate, checked in both binaries — and a field
that says so is better than one that is always absent. **Which operator a driver
will not take is what the operator support matrix answers**, by asking with one
operator at a time; see
[`docs/OPERATOR-MATRIX.md`](OPERATOR-MATRIX.md). That is the reason it exists.

`nnapiErrno` appears when NNAPI's delegate reports an error through
`hasErrors()` — the one thing about a delegate that comes from an API rather
than from prose.

### The delegate's own words

`"delegateLog": true` in the request (or `--delegate-log`) returns what the
delegate printed, unparsed and capped at 4000 characters. It also arrives
**unasked whenever the attribution failed**, since that is when it is needed and
nobody thinks to ask in advance.

It is also the only place a **reason** could come from, and it does not carry
one for NNAPI. Checked against the library this app ships rather than asserted:
`libtensorflowlite_jni.so` contains XNNPACK's per-node reasons —

```
failed to delegate %s node #%d. adj_x is not supported
failed to delegate %s node #%d. Unsupported number of dimensions %d for tensor #%d, must be at least 3
```

— and **no NNAPI equivalent**. That delegate's strings in the binary are
API-version and diagnostics wording; its validator's reasons are not compiled
in as messages. So when an NNAPI driver declines a graph, nothing here knows
why, and no field could be added that would ([#158](https://github.com/m96-chan/DroidRunner/issues/158)).

The gap is expensive and has been paid once. A `CONV_2D` accelerated by one
project and refused by another — same driver, same phone — took four device
sweeps and a byte-level flatbuffer diff to explain: the accepted model's filter
was a compile-time constant and the refused one's was a graph input. A cell
saying `unsupported` was a claim about a model and read as a claim about an
operator. See *What a cell does not say* in
[`OPERATOR-MATRIX.md`](OPERATOR-MATRIX.md).

Read it when you doubt us. Our `executed` is a regex over that text, and where
the two disagree the text is what happened. A real one, from an MT6899:

```
VERBOSE: Replacing 5 out of 64 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 5 partitions for the whole graph.
VERBOSE: Replacing 59 out of 62 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 5 partitions for the whole graph.
```

Two delegates in one build: NNAPI took 5 nodes of 64, XNNPACK then took 59 of
the remaining 62. The **last** line is the one that decides, which is why that
result is `cpu-fallback` and not a partial acceleration.

### The conditions it was measured under

A phone is not a stable benchmark host, and a job that starts cool can finish
throttled. `conditions` reports what the device was doing at **each end of the
timing loop**, so a regression gate can tell a slower kernel from a warmer
phone. A gate that fires on both gets muted inside a week.

`stable` is the blunt version and the field to branch on: **true only when both
ends reported a thermal status and the two agree.** A device that would not say
counts as unstable — silence is not a yes, and a gate must not read "we could
not tell" as "it was fine". Any field the device could not read is absent
rather than zero, because zero is a real battery temperature.

`thermalHeadroom` in particular is often present at one end and not the other,
and on some phones at neither: the platform rate-limits it to roughly one
reading every ten seconds and returns NaN otherwise, and not every vendor
implements it. Measured on an SM8650 (present at `before`, absent at `after`
after a 20-iteration loop) and an MT6899 (absent at both). It is a bonus, not
the field to branch on — `stable` is.

`p90Us` and `p99Us` sit beside min/median/max because a throttle shows in the
tail and not in the middle. They are nearest-rank, so every value published was
produced by some iteration. `timings: true` in the request adds `timingsUs`:
every iteration **in the order it ran**, which is the only view in which a
throttle developing is visible at all. It is off by default — 500 iterations is
500 numbers.

### Optional pieces

- `outputFiles` — present when `outputDir` was given. Paths are **as the job
  sees them**, under `/home/runner`.
- `quantizationParams` — on quantized tensors only, so a caller holding int8
  bytes is not inferring a scale from the numbers.
- `precisionLossAllowed` — GPU only: whether the delegate was allowed to drop
  to fp16. It never does so unasked, and this says which was asked for — the one
  path where the precision of a result is a declared choice rather than
  something to be discovered afterwards.
- `inputBytes` / `outputBytes` — on a failure with a live interpreter: the sizes
  it settled on. A buffer-size failure carries no message of its own, and
  guessing the shapes from outside cost two device round trips once.
- `backend` — Qualcomm only: which QNN backend ran it, e.g. `htp`.
- `profilingBytes` — Qualcomm only: how much profiling data the delegate
  produced. Zero is normal; it exists because a run that reports nothing *and*
  profiles nothing cannot be attributed at all, and is refused.
- `baseline` — present when `baseline: true` was asked for: a complete result
  for the same model with no delegate, measured in the same request and so at
  the same thermal state.

## The wrapper

Machine output goes to **stdout and nowhere else**; everything meant for a
person goes to stderr. `--output FILE` writes the same JSON to a file, so a job
can hand it to `upload-artifact` without a redirect that also catches a warning
line.

`devices --json` and `bench-all --json` return an object with a `results` array
— the shape a batch request will also return, so a consumer written against one
reads the other.

## A batch

`POST /v1/tests/models`, or `droidrunner-device test batch manifest.json`, where
the manifest is a JSON array:

```json
[{"id": "conv-int8", "path": "/home/runner/ops/conv-int8.tflite", "device": "qnn-htp"},
 {"id": "pack-fp32", "path": "/home/runner/ops/pack-fp32.tflite", "iterations": 0}]
```

```json
{"schema": 1, "ok": true,
 "results": [ {"id": "conv-int8", "ok": true,  "executed": "accelerator", "…": "…"},
              {"id": "pack-fp32", "ok": false, "code": "refused", "…": "…"} ]}
```

- **One entry back per entry sent, in order.** A failing row never ends the
  sweep — a sweep is largely *made of* rejections, and each one is the data.
  A malformed row comes back saying so rather than shortening the array.
- `iterations: 0` means load, delegate and allocate but do not time. Half of a
  sweep only asks whether a graph was accepted, and that answer is complete
  once tensors are allocated. The result then carries `executed` and
  `delegation` and no timings.
- `budgetMs` caps the **whole** sweep. If it runs out, everything collected so
  far comes back with `budgetExhausted: true` and `stoppedAt` naming the row
  that was running — which is the only thing a caller can act on when one
  driver will not return.

## What is not in the contract

- Field **order**. It is JSON.
- The wording of `error`, or of `delegation.describe`.
- Anything printed to stderr.
- Timings as a promise about the hardware. A phone is not a stable benchmark
  host; what state it was in is reported under `conditions`, and it is the
  caller's to weigh.
