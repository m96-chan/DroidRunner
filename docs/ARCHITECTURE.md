# DroidRunner architecture

## Trust and process boundaries

1. **Android controller** owns credentials, runtime installation, policy, and UI.
2. **PRoot guest** runs the unmodified official Linux ARM64 Actions Runner.
3. **Device Agent** exposes an authenticated loopback API for Android-only hardware tests.
4. **GitHub job** is untrusted input and receives only a per-job Device Agent capability.

The PAT never enters the Linux guest. The controller exchanges it for GitHub's one-hour
registration token and passes only that token to `config.sh`. After registration, the
official runner stores its own credentials below the app-private runtime directory.

## Device pool routing

Use labels to make heterogeneous phones schedulable:

| Purpose | Labels |
| --- | --- |
| Any phone | `self-hosted, android, arm64` |
| Any NPU candidate | `self-hosted, android, android-npu` |
| Qualcomm, verified | `android-npu, npu-qnn` |
| Google Tensor | `android-npu, npu-tflite` |
| MediaTek | `android-npu, npu-neuron` |
| Samsung Exynos | `android-npu, npu-enn` |

SoC name detection is only a hint, and `npu-qnn` no longer comes from it: a Snapdragon
earns that label by running a model on its Hexagon, because NNAPI cannot reach one and a
name-derived label sent jobs to a device that quietly used its CPU. The remaining vendor
labels are still hints; `nnapi`, `nnapi-accelerator` and `npu-qnn` are measurements.

## Device Agent protocol

Bound to `127.0.0.1` only, and every request carries a capability token issued per
job and delivered through a file in the app-private runtime directory — loopback is
shared with every app on the phone, so the socket alone is not a boundary.

| | |
| --- | --- |
| `GET /v1/capabilities` | SDK, ABI, thermal state, the NNAPI drivers this phone exposes, what its Hexagon would need, and which DroidRunner build is answering |
| `POST /v1/tests/nnapi` | a built-in ADD graph — the cheapest proof a driver executes anything |
| `POST /v1/tests/conv` | a built-in CONV_2D benchmark, per driver |
| `POST /v1/tests/model` | **a model the job supplied**, on the driver it names, with its own inputs and outputs |
| `POST /v1/tests/models` | the same, as a manifest — a few hundred models in one request |

The last two are what a consumer actually uses; the first two predate them. Paths
in a request are the job's own view (`/home/runner/...`) and are proven to stay
inside the runner home before anything is opened, because job code is untrusted.

Every response follows [`RESULT-CONTRACT.md`](RESULT-CONTRACT.md): a `schema`, a
`code` from a closed set when something fails, and — for anything timed — who
executed it and what state the phone was in while it did.

Arbitrary native libraries are not loaded into the controller process. Qualcomm's
runtime runs in a separate `:qnn` process, which is a licensing boundary as much
as a safety one: PRoot is GPL-2.0 and lives in the main process, and the FSF's
line for "one program" is the shared address space.

## Measuring, and knowing the measurement is worth something

Three things sit behind the numbers, and each exists because of a specific way
an earlier version of this was wrong:

- **Attribution.** `executed`/`executedBy` name the delegate that took the nodes
  *and* the driver it was pinned to. A CPU run through `nnapi-reference` reads
  identically to an accelerated one without both halves, and once did.
- **The operator matrix.** One model per operator per precision, run on every
  driver, with a CPU control so a broken model is excluded rather than filed as
  an operator a driver refuses. A whole network cannot answer which operator
  failed, because one refused node pushes its whole partition back to the CPU.
  See [`OPERATOR-MATRIX.md`](OPERATOR-MATRIX.md).
- **Comparison over time.** Each phone's matrix is committed under
  `docs/matrices/`, and the next run is compared against it: a driver that stops
  taking an operator after an OTA turns a build red. This is the check that needs
  the same phone kept rather than a machine rented.

The delegation split is read out of text TFLite prints — there is no API for it —
so `tools/check-tflite-wording.sh` fails the build when the wording it parses
leaves the shipped library, and `delegateLog` returns the delegate's own words
alongside our reading of them.

## Scheduling policy

The controller should admit a new job only when all configured requirements hold:

- charging when `requireCharging` is enabled;
- battery at or above the configured threshold;
- Android thermal status below `SEVERE`;
- enough free app-private storage for the declared job budget.

If a device becomes too hot during a job, stop accepting new work first. Do not terminate
an active build unless the status reaches a critical/emergency threshold.

## Runtime distribution

The runtime bundle is large and should be a separate GitHub Release asset rather than an
APK asset. HTTPS plus SHA-256 detects corruption, but the manifest and archive must share
an independently trusted distribution channel to resist replacement. Production releases
should add a signature verified by a public key embedded in the APK.

Installing replaces the whole runtime directory, so the stored registration details are
carried into the new tree while the runner's own identity files are not: the details say
what this device registered as, the identity belongs to the runtime being replaced. That
keeps installing a runtime and registering a runner separate — a device that lost its
runtime can be repaired without re-registering, and the service registers again from the
carried details the first time it finds no identity.
