# Runtime: proot + runtime bundle

DroidRunner splits the Linux runtime into two parts:

1. **proot (inside the APK)** — Android 10+ refuses to `exec()` binaries stored
   in app data, so proot and its ELF loaders are cross-compiled with the NDK
   and shipped as jniLibs (`libproot.so`, `libproot-loader.so`,
   `libproot-loader32.so`). The app executes them from `nativeLibraryDir`,
   which is the only writable-by-nobody, executable location an app gets.
   Everything inside the rootfs is started by proot's loader, never `exec()`ed
   directly, so the bundle itself can stay plain data.
2. **Runtime bundle (downloaded on first setup)** — a data-only tarball with
   the glibc rootfs and the official GitHub Actions runner. Verified against
   the SHA-256 in the manifest before activation.

## Building proot (`build-proot.sh`)

Cross-compiles talloc and the Android-patched [termux/proot] with the NDK and
installs the results into `app/src/main/jniLibs/arm64-v8a/`. Run once before
building the APK:

```bash
ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/<version> runtime/build-proot.sh
```

The build recipe (cross-answers file, string-header patch, unbundled loader)
is adapted from the [Godot Android Editor Build Environment (GABE)][gabe]
proot build. The pinned proot commit and talloc version live at the top of the
script. CI runs this step automatically before assembling the APK.

## Building the bundle (`build-bundle.sh`)

Produces `droidrunner-runtime-arm64.tar.gz` + `runtime-manifest.json`:

```text
rootfs/            Ubuntu base (arm64) + runner dependencies (libicu, krb5,
                   lttng-ust, zlib, git, curl, ca-certificates)
home/runner/       official actions/runner linux-arm64 release
```

Requirements: an ARM64 Linux host, root (the dependency install chroots into
the rootfs). The easiest way to build is the **Runtime bundle** GitHub Actions
workflow (`ubuntu-24.04-arm`):

- Run it manually from the Actions tab.
- Leave `release_tag` empty to get the bundle as a workflow artifact.
- Set `release_tag` (e.g. `runtime-0.1.0`) to publish a GitHub release with
  the bundle and a `runtime-manifest.json` whose URL already points at the
  release asset — paste the manifest's raw download URL into the app.

Hard links are dereferenced at packaging time because the in-app extractor
creates only files, directories, and symlinks.

## Manifest format

```json
{
  "version": "runner-2.337.0-ubuntu-24.04.3",
  "url": "https://github.com/OWNER/DroidRunner/releases/download/runtime-0.1.0/droidrunner-runtime-arm64.tar.gz",
  "sha256": "..."
}
```

The APK refuses a bundle whose SHA-256 does not match. Manifest signature
verification (public key embedded in the APK) is on the roadmap.

## License notes

proot is GPL-2.0, talloc is LGPL-3.0, and the rootfs contains the usual
Ubuntu package licenses. `build-proot.sh` pins the exact proot commit and
talloc tarball it builds, which is the corresponding source for a shipped
APK. When distributing APKs or bundles, keep those pins public alongside the
patches in `runtime/patches/`.

[termux/proot]: https://github.com/termux/proot
[gabe]: https://github.com/godotengine/android-editor-buildenv-app
