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
| `invalid-request` | malformed, or a path outside the job's home | `1` |
| `failed` | anything else that stopped a run | `1` |
| — | the agent did not answer | `4` |

`4` is the one worth stopping a sweep for. So, differently, is `invalid-model`:
a refusal is a row of data and the sweep carries on, while a file nothing can
load will fail every remaining row identically, and the fault is the caller's.
It is told apart from `failed` by loading the model again with **nothing
attached** — the same control the operator matrix runs against the CPU — so it
is a statement about the file and not about any driver.

Reported by the first consumer outside this project, whose model was rejected
before any delegate saw it and arrived as a bare `failed`:

```
Cannot create interpreter: BytesRequired number of bytes overflowed.
Tensor 0 is invalidly specified in schema.
```

`error` carries that text in full, naming each tensor. It is deliberately not
summarised — it is what turns an afternoon into a minute. On the Qualcomm path
`detail` carries the **vendor's** own error string beside it, which is empty
when the failure was upstream of the delegate, as it is here. Empty is the
honest value: QNN was never asked.

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
delegate — there is no API for it, in 2.16.1 or anywhere we could find — so a
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
