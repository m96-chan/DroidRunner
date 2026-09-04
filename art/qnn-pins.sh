#!/usr/bin/env bash
# Regenerates the pinned digests in npu/QnnArtifacts.kt.
#
# Maven Central publishes only SHA-1 and MD5 beside these artifacts, so there is
# nothing worth verifying a download against unless we compute a SHA-256 once
# ourselves and commit it. Run this when bumping the QNN release, then paste the
# rows it prints into QnnArtifacts.kt and update VERSION.
#
#   art/qnn-pins.sh 2.49.0
set -euo pipefail

version="${1:?usage: qnn-pins.sh <qnn version, e.g. 2.49.0>}"
base=https://repo1.maven.org/maven2/com/qualcomm/qti
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for module in qnn-runtime qnn-litert-delegate; do
  echo "fetching $module-$version.aar" >&2
  curl -fsSL -o "$work/$module.aar" "$base/$module/$version/$module-$version.aar"
  unzip -q -o "$work/$module.aar" -d "$work/$module" 'jni/arm64-v8a/*'
done

row() { # <module enum> <path>
  local library
  library="$(basename "$2")"
  printf '        Entry(Module.%s, "%s", "%s", %s),\n' \
    "$1" "$library" "$(sha256sum "$2" | cut -d' ' -f1)" \
    "$(stat -c%s "$2" | sed ':a;s/\B[0-9]\{3\}\>/_&/;ta')"
}

echo "    private val SHARED = listOf("
for library in libQnnHtp.so libQnnSystem.so libQnnHtpPrepare.so; do
  row RUNTIME "$work/qnn-runtime/jni/arm64-v8a/$library"
done
for library in libQnnTFLiteDelegate.so libqnn_delegate_jni.so; do
  row DELEGATE "$work/qnn-litert-delegate/jni/arm64-v8a/$library"
done
echo "    )"
echo
echo "    private val HEXAGON = mapOf("
for skel in "$work"/qnn-runtime/jni/arm64-v8a/libQnnHtpV*Skel.so; do
  htp="$(basename "$skel" | sed 's/libQnnHtpV\([0-9]*\)Skel\.so/\1/')"
  echo "        $htp to listOf("
  row RUNTIME "$skel"
  row RUNTIME "$(dirname "$skel")/libQnnHtpV${htp}Stub.so"
  echo "        ),"
done
echo "    )"
