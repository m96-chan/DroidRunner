---
name: release
description: Cut a DroidRunner release — the ordering, key handling and on-device checks that CI cannot do. Use when publishing an app version (v*) or a runtime bundle (runtime-*), or when asked to "release", "cut a version", "リリース".
---

# Releasing DroidRunner

CI builds, signs and publishes. It cannot judge **order**, cannot install on a
phone, and cannot tell whether the thing it published actually works. That is
what this covers.

## What CI already does — do not redo it by hand

| Workflow | Does |
| --- | --- |
| `app-release.yml` (on `v*` tag) | proot NDK build, unit tests, signed release APK, GitHub Release |
| `runtime.yml` (manual, `release_tag`) | builds the bundle, signs the manifest, publishes both |

Both refuse to publish unsigned: no `ANDROID_KEYSTORE_BASE64` → the APK build
fails; no `RUNTIME_SIGNING_KEY` → the runtime release fails. Those guards exist
because an unsigned release is worse than no release (see *Keys* below).

## Order matters, and getting it wrong strands new users

An app build carrying `droidrunner.runtimeSigningKeys` **refuses an unsigned
manifest**. So when signing is introduced or a key changes:

1. Publish the **signed runtime** first (`runtime.yml` with a `release_tag`).
2. Only then tag the app.

Reversed, every *new* setup fails at "Install runtime" while existing devices
keep working — a failure that will not show up in any test you run locally.

The same shape applies to any change that makes the app stricter about what the
runtime provides: ship the runtime that satisfies it, then the app.

## Keys: what breaks if they are wrong

Two independent keys, different consequences:

- **APK signing key** (`ANDROID_KEYSTORE_BASE64`). Android refuses to upgrade
  across a signature change, and reinstalling DroidRunner **discards the runner
  registration and stored GitHub credentials**. Losing this key means every user
  re-registers every device. It is also why a debug-signed release must never
  escape.
- **Runtime manifest key** (`RUNTIME_SIGNING_KEY`, public half in
  `gradle.properties`). Rotatable: trust both keys (comma-separated), publish a
  release signed with the new one, then drop the old. No user action needed.

Private keys live outside git — `.gitignore` covers `*.jks`, `*.pem`, `*.p12`,
`*.key`. Before any release, confirm none is staged:

```bash
git status --porcelain | grep -iE '\.(jks|pem|p12|key)$' && echo "STOP: key staged"
```

## Version numbers come from the tag

`versionName`/`versionCode` derive from the `v*` tag
(`major*1_000_000 + minor*1_000 + patch`); an untagged build reports
`0.0.0-dev` / code 1. Nothing to edit by hand — and **do not** hand-edit
`app/build.gradle.kts` to bump a version.

To test an upgrade locally, build a debug APK carrying a higher version:

```bash
./gradlew assembleDebug -Pdroidrunner.releaseTag=v0.4.0
```

(Use `-P`, not the env var: a long-lived Gradle daemon may not see a changed
environment.)

## Before tagging

```bash
git checkout main && git pull --ff-only
./gradlew clean
ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/<version> runtime/build-proot.sh
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Rebuild proot from source rather than trusting `app/src/main/jniLibs/` — it is
gitignored, so a stale local copy is invisible to review.

Then **install on a device and run a real job**. Every serious bug this project
has hit was found here and not by tests: NNAPI tensor sizing, the listener
losing its session, MIUI killing background work, `config.sh` refusing a
leftover `.runner_migrated`. A green CI run means the code compiles, not that a
phone can serve CI.

Minimum on-device pass:

1. install, launch, no crash
2. runner reaches `listening for jobs`
3. dispatch `Device CI smoke` — it must complete
4. if the release touches the agent, dispatch `Device NPU probe` too

## Tag, then verify the artifact

```bash
git tag -a v0.4.0 -m "…" && git push origin v0.4.0
```

When the workflow finishes, check what was actually published — not what you
expected to publish:

```bash
gh release download v0.4.0 --pattern '*.apk' -D /tmp/rel
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging /tmp/rel/droidrunner-v0.4.0.apk | head -1
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs /tmp/rel/droidrunner-v0.4.0.apk
```

Confirm: package `io.github.m96chan.droidrunner`, the expected
versionName/versionCode, and a certificate SHA-256 **identical to the previous
release** — a changed fingerprint means users cannot upgrade.

## Release notes: what CI's generated list leaves out

`--generate-notes` produces a PR list. Prepend what a reader actually needs:

- **Whether it updates in place.** State it either way. If it does not (package
  or key changed), say what is lost — registration and credentials — and why the
  change was made before more people installed.
- What changed **for someone running a device**, not the commit titles.
- Install instructions: direct download, and Obtainium with the
  `droidrunner-v.*\.apk` filter.
- The signing certificate fingerprint, so a third party can verify.
- The standing warning: a self-hosted runner executes workflow code on the phone.

## Device quirks that waste time

- **Xiaomi/MIUI** blocks `adb install` until *Install via USB* is enabled, and
  silently ignores `adb shell input tap` without *USB debugging (Security
  settings)*. A tap that does nothing is usually this, not a UI bug.
- **Screen coordinates differ per device.** Taps computed for one phone hit
  nothing on another; screenshot first.
- **Android refuses downgrades.** A release build on the device blocks a plain
  local debug build — use `-Pdroidrunner.releaseTag=` with a higher version.
- **Reinstalling orphans the runner session.** GitHub still holds it, so queued
  jobs sit unassigned until the listener retries
  (`A session for this runner already exists` → `Runner reconnected`). Not a
  failure; give it a few minutes or restart the runner.
- Android SDK setup in CI occasionally fails with `Error on ZipFile unknown
  archive`. Infrastructure, not the code — rerun the job.

## After releasing

- Close the issues the release ships, quoting what was verified on hardware.
- Update the roadmap and the status table if they still claim the work is
  pending — they drift, and they are what a reader judges the project by.
