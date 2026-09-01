#!/usr/bin/env bash
#
# Cross-compiles talloc + termux/proot for Android ARM64 with the NDK and
# installs the results as app jniLibs, so the APK can execute them from
# nativeLibraryDir (Android 10+ forbids exec() from app data storage).
#
# Adapted from the Godot Android Editor Build Environment (GABE) proot build:
# https://github.com/godotengine/android-editor-buildenv-app (MIT).
# proot and talloc keep their own licenses (GPL-2.0 / LGPL-3.0); see
# runtime/README.md for source/compliance notes.
#
# Usage: ANDROID_NDK_ROOT=... runtime/build-proot.sh
set -euo pipefail

ANDROID_API="${ANDROID_API:-28}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"

TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
TALLOC_SHA256="${TALLOC_SHA256:-dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd}"

PROOT_REPO="${PROOT_REPO:-https://github.com/termux/proot.git}"
PROOT_COMMIT="${PROOT_COMMIT:-7266fb3e8516535682f5a9c8f3a7e70f6506eddb}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/out/proot"
JNILIBS_DIR="${JNILIBS_DIR:-$SCRIPT_DIR/../app/src/main/jniLibs/arm64-v8a}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[ -n "${ANDROID_NDK_ROOT:-}" ] || die "ANDROID_NDK_ROOT must be set"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC_BIN="$TOOLCHAIN/aarch64-linux-android${ANDROID_API}-clang"
AR_BIN="$TOOLCHAIN/llvm-ar"
[ -x "$CC_BIN" ] || die "NDK clang not found at $CC_BIN (set HOST_TAG?)"
[ -x "$AR_BIN" ] || die "llvm-ar not found at $AR_BIN"

mkdir -p "$BUILD_DIR"

##
## talloc (static library, cross-compiled with waf cross-answers)
##

TALLOC_DIR="$BUILD_DIR/talloc"
TALLOC_SRC="$TALLOC_DIR/source"
if [ ! -e "$TALLOC_SRC" ]; then
    mkdir -p "$TALLOC_SRC"
    archive="$TALLOC_DIR/talloc.tar.gz"
    wget -qO "$archive" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
        || die "Unable to download talloc"
    echo "$TALLOC_SHA256  $archive" | sha256sum -c - >/dev/null \
        || die "talloc SHA-256 mismatch"
    tar -xf "$archive" --strip-components=1 -C "$TALLOC_SRC" \
        || die "Unable to extract talloc"
fi

pushd "$TALLOC_SRC" >/dev/null
make distclean >/dev/null 2>&1 || true
CC="$CC_BIN" ./configure build --disable-rpath --disable-python \
    --cross-compile --cross-answers="$SCRIPT_DIR/talloc-answers.txt" \
    || die "Unable to build talloc"
mkdir -p "$TALLOC_DIR/lib" "$TALLOC_DIR/include"
"$AR_BIN" rcs "$TALLOC_DIR/lib/libtalloc.a" bin/default/talloc*.o \
    || die "Unable to archive libtalloc.a"
cp talloc.h "$TALLOC_DIR/include/"
popd >/dev/null

##
## proot (termux fork, Android-patched)
##

PROOT_SRC="$BUILD_DIR/proot-source"
if [ ! -e "$PROOT_SRC/.git" ]; then
    git clone "$PROOT_REPO" "$PROOT_SRC" || die "Unable to clone proot"
fi
git -C "$PROOT_SRC" fetch --quiet origin "$PROOT_COMMIT" 2>/dev/null || true
git -C "$PROOT_SRC" checkout --quiet "$PROOT_COMMIT" || die "Unable to checkout $PROOT_COMMIT"
git -C "$PROOT_SRC" checkout -- .
patch -d "$PROOT_SRC" -p1 < "$SCRIPT_DIR/patches/string-header.patch" || die "Unable to patch proot"

CPPFLAGS="-I$TALLOC_DIR/include" LDFLAGS="-L$TALLOC_DIR/lib" \
    make -C "$PROOT_SRC/src" \
    CC="$CC_BIN" CROSS_COMPILE="$TOOLCHAIN/llvm-" \
    PROOT_UNBUNDLE_LOADER=1 HAS_LOADER_32BIT=1 V=1 proot \
    || die "Unable to build proot"

##
## Install as jniLibs (lib*.so names so the platform extracts/execs them)
##

mkdir -p "$JNILIBS_DIR"
cp "$PROOT_SRC/src/proot" "$JNILIBS_DIR/libproot.so"
cp "$PROOT_SRC/src/loader/loader" "$JNILIBS_DIR/libproot-loader.so"
cp "$PROOT_SRC/src/loader/loader-m32" "$JNILIBS_DIR/libproot-loader32.so"

echo "proot installed to $JNILIBS_DIR:"
ls -la "$JNILIBS_DIR"
