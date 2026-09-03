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
| Qualcomm | `android-npu, npu-qnn` |
| Google Tensor | `android-npu, npu-tflite` |
| MediaTek | `android-npu, npu-neuron` |
| Samsung Exynos | `android-npu, npu-enn` |

SoC name detection is only a hint. The Device Agent must run a real backend probe before
advertising a backend as usable in the final design.

## Device Agent protocol (next milestone)

Bind only to `127.0.0.1` and require a random capability token on every request.

- `GET /v1/capabilities`: SDK, ABI, thermal state, memory, available accelerator adapters.
- `POST /v1/tests/nnapi`: run a bundled NNAPI graph and return latency/error metrics.
- `POST /v1/tests/vendor/{adapter}`: run an installed, allowlisted vendor adapter.
- `POST /v1/artifacts`: copy result artifacts into the runner work directory.

Arbitrary native libraries must not be loaded into the controller process. Vendor test
adapters should execute in an isolated Android service and accept only declared models,
inputs, timeouts, and output paths.

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
