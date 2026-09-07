#!/usr/bin/env bash
#
# `executed` is promised on every path from v0.8.0 (issue #158).
#
# The contract tells consumers they may require appVersion >= 0.8.0 and delete
# the shim that reconstructs the field from `delegation`. That promise is only
# as good as both result builders continuing to emit it — and the Qualcomm one
# has no automated coverage at all, because exercising it needs a Snapdragon
# with a runtime installed. It went missing there once already, and a whole
# operator matrix came back reading 0 of 62 because absence was read as refusal.
#
# So this is a source-level canary, in the spirit of check-tflite-wording.sh:
# it cannot prove the field is correct, only that nobody deleted it.
set -euo pipefail

die() { echo "FAIL: $*" >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Every place a model result is built. A new one belongs in this list on the
# day it is written, not on the day somebody notices it answers differently.
paths=(
    "app/src/main/java/io/github/m96chan/droidrunner/npu/ModelRunner.kt"
    "app/src/main/java/io/github/m96chan/droidrunner/qnn/QnnModelRunner.kt"
)

for path in "${paths[@]}"; do
    [ -f "$path" ] || die "$path is gone; if a result builder moved, this list has to move with it"
    grep -q '"executed"' "$path" \
        || die "$path no longer reports \`executed\`, which the contract promises on every path from v0.8.0"
    echo "  ok   $(basename "$path") reports executed"
done

echo "every result builder still reports executed"
