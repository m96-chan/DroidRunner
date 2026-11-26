#!/usr/bin/env bash
#
# Runs the GuestFs syscall layer (app/src/main/cpp/guest_fs.c) on a connected
# phone, without installing or touching the app.
#
# The unit tests run the same C on the host. This checks that an Android kernel
# and its SELinux policy refuse the same symlinks. The test binary runs as the
# adb shell user in a scratch directory under /data/local/tmp, which it removes
# afterwards.
#
# Usage: tools/test-guest-fs-on-device.sh [adb serial]
# Needs: ANDROID_NDK_ROOT, or an NDK under $ANDROID_HOME/ndk.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$HERE/../app/src"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_ROOT:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "no NDK found; set ANDROID_NDK_ROOT" >&2; exit 1; }

case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64 ;;
    Linux) HOST=linux-x86_64 ;;
    *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
# minSdk, so the binary runs on the oldest kernel the app supports.
CC="$NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android28-clang"

ADB=(adb)
[ $# -gt 0 ] && ADB=(adb -s "$1")

BUILD="$(mktemp -d)"
REMOTE=/data/local/tmp/droidrunner-guest-fs-test
trap 'rm -rf "$BUILD"; "${ADB[@]}" shell rm -rf "$REMOTE" >/dev/null 2>&1 || true' EXIT

"$CC" -Wall -Wextra -Werror -O2 -I"$APP/main/cpp" \
    "$APP/test/native/guest_fs_test.c" "$APP/main/cpp/guest_fs.c" \
    -o "$BUILD/guest_fs_test"

"${ADB[@]}" shell rm -rf "$REMOTE"
"${ADB[@]}" shell mkdir -p "$REMOTE/scratch"
"${ADB[@]}" push "$BUILD/guest_fs_test" "$REMOTE/" >/dev/null
"${ADB[@]}" shell chmod 755 "$REMOTE/guest_fs_test"
"${ADB[@]}" shell "uname -r; $REMOTE/guest_fs_test $REMOTE/scratch"
