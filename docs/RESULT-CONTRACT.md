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
| `unknown-device` | no such accelerator on this phone | `3` |
| `not-installed` | the vendor runtime this device would need is not installed | `3` |
| `invalid-request` | malformed, or a path outside the job's home | `1` |
| `failed` | anything else that stopped a run | `1` |
| — | the agent did not answer | `4` |

`4` is the one worth stopping a sweep for.

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

`executedBy` names **both halves** — the delegate that claimed the nodes and the
driver it was pinned to, e.g. `TfLiteNnapiDelegate:nnapi-reference`. That
example is why: `accelerator` alone reads identically for a graph NNAPI handed
to its CPU reference.

`unsupportedOps` appears when a delegate said which operators it would not
take. "12 of 14 nodes went to the accelerator" says an operator table is wrong
somewhere; this says where.

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

## What is not in the contract

- Field **order**. It is JSON.
- The wording of `error`, or of `delegation.describe`.
- Anything printed to stderr.
- Timings as a promise about the hardware: a phone is not a stable benchmark
  host, and what state it was in is [#98](https://github.com/m96-chan/DroidRunner/issues/98).
