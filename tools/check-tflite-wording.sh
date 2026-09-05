#!/usr/bin/env bash
#
# Fails when TFLite stops printing what we parse (issue #128).
#
# Everything this project says about *who ran a graph* — `executed`,
# `executedBy`, `delegation`, and the whole operator matrix — comes from a
# regex over lines the interpreter prints while applying a delegate. There is
# no API for it: TFLite 2.16.1's `InterpreterApi` exposes tensors and timings
# and nothing about partitioning, and `NnApiDelegate` offers only an errno.
#
# So the wording is load-bearing, and a TFLite upgrade that rewords one line
# turns every result into `executed: unknown` with nothing failing anywhere.
# This is the thing that fails: it reads the format strings out of the library
# actually being shipped and checks the ones the parser depends on are still
# there.
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

# The library as the build resolved it, not as a version number suggests, and
# the ABI the phones run — preferring what was actually packaged over whatever
# a cache happens to hold.
SO="${1:-}"
if [ -z "$SO" ]; then
    for candidate in \
        app/build/intermediates/merged_native_libs/*/*/out/lib/arm64-v8a/libtensorflowlite_jni.so \
        "$HOME"/.gradle/caches/*/transforms/*/transformed/tensorflow-lite-*/jni/arm64-v8a/libtensorflowlite_jni.so
    do
        [ -f "$candidate" ] && { SO="$candidate"; break; }
    done
fi
[ -n "$SO" ] && [ -f "$SO" ] || die "cannot find libtensorflowlite_jni.so; build once first"
echo "checking $SO" >&2

command -v strings >/dev/null || die "strings is required"

# Extracted once, into a file. `strings ... | grep -q` looks obvious and is
# wrong here: grep exits at the first match, strings takes SIGPIPE, and under
# `set -o pipefail` the whole pipeline then reports failure — so every check
# passed and every check reported FAIL.
STRINGS="$(mktemp)"
trap 'rm -f "$STRINGS"' EXIT
strings "$SO" > "$STRINGS"

# Exactly as it appears in the binary. Not a regex: if this string changes at
# all, the parser's regex needs looking at, and a near-match is the case worth
# catching.
expect() {  # expect <description> <literal format string>
    if grep -qF "$2" "$STRINGS"; then
        echo "  ok   $1" >&2
    else
        echo "  FAIL $1" >&2
        echo "       expected the library to contain:" >&2
        echo "         $2" >&2
        echo "       Delegation.parse is written against it. Look at what the" >&2
        echo "       new build prints before changing the regex." >&2
        exit 1
    fi
}

expect "TFLite announces the partitioning, and names the delegate that took it" \
    'Replacing %d out of %d node(s) with delegate (%s) node, yielding %zu partitions'

echo "the wording Delegation.parse depends on is still in the library" >&2
