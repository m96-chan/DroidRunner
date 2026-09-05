#!/usr/bin/env bash
#
# Tests for `droidrunner-device` (issue #125).
#
# The wrapper is the only path a consumer takes, it ships as an APK asset, and
# nothing compiled it or imported it — so until this existed the exit statuses
# published in docs/RESULT-CONTRACT.md had nothing behind them. One of them was
# wrong: `bench-all` printed its table and exited 1, and a phone found it.
#
# Everything here runs against a stub agent on loopback. No device, no Android.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${WRAPPER:-$HERE/../droidrunner-device}"
PORT="${PORT:-41997}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; [ -n "${AGENT_PID:-}" ] && kill "$AGENT_PID" 2>/dev/null' EXIT

passed=0
failed=0

# Test names are sentences, and the failure prints what it wanted beside what
# it got — a bare "assertion failed" in a shell test is a second debugging
# session.
check() {  # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        passed=$((passed + 1))
        printf '  ok   %s\n' "$1"
    else
        failed=$((failed + 1))
        printf '  FAIL %s\n       wanted: %s\n       got:    %s\n' "$1" "$2" "$3"
    fi
}

contains() {  # contains <name> <needle> <haystack>
    case "$3" in
        *"$2"*) passed=$((passed + 1)); printf '  ok   %s\n' "$1" ;;
        *) failed=$((failed + 1))
           printf '  FAIL %s\n       expected to contain: %s\n       got: %s\n' "$1" "$2" "$3" ;;
    esac
}

# What the agent will say next. Written before each case rather than baked in,
# so a test reads as "given the device says X, the wrapper does Y".
says() { printf '%s' "$1" > "$WORK/response.json"; }
capabilities() { printf '%s' "$1" > "$WORK/capabilities.json"; }
sent() { cat "$WORK/last-request.json" 2>/dev/null; }

# Prints the wrapper's stdout and records its exit status in a file.
#
# A file, not a variable: half the calls here are `out="$(run ...)"`, and a
# command substitution is a subshell, so an assignment inside it never reaches
# the caller. The first version of this checked `$status` after such a call and
# was silently re-asserting the previous case's exit code — it agreed with two
# tests that were never run.
# Named exit-status, not status: the stub reads DIR/status as the HTTP status
# to answer with, and the first version of this wrote the wrapper's exit code
# there. One `4` turned every later POST into an HTTP 4, which curl rejects, so
# the wrapper reported the agent unreachable and wrote 4 again — a failure that
# fed itself and made all of them look like the same bug.
run() {  # run <args...> -> prints stdout; exit status via status_of
    set +e
    OUT="$("$WRAPPER" "$@" 2>"$WORK/stderr")"
    printf '%d' $? > "$WORK/exit-status"
    set -e
    printf '%s' "$OUT"
}

status_of() { cat "$WORK/exit-status"; }

export DROIDRUNNER_DEVICE_URL="http://127.0.0.1:$PORT"
export DROIDRUNNER_DEVICE_TOKEN_FILE="$WORK/token"
echo "stub-token" > "$WORK/token"
unset DROIDRUNNER_DEVICE_TOKEN

python3 "$HERE/stub-agent.py" "$PORT" "$WORK" &
AGENT_PID=$!
for _ in $(seq 1 50); do
    curl -fsS "$DROIDRUNNER_DEVICE_URL/v1/capabilities" >/dev/null 2>&1 && break
    sleep 0.1
done

echo "the exit statuses docs/RESULT-CONTRACT.md publishes"

says '{"schema":1,"ok":true,"avgUs":12.5,"executed":"accelerator"}'
run test model "$WORK/model.tflite" >/dev/null 2>&1 || true
: > "$WORK/model.tflite"
run test model "$WORK/model.tflite" >/dev/null
check "a run that worked exits 0" 0 "$(status_of)"

says '{"schema":1,"ok":false,"code":"refused","error":"took no operators"}'
run test model "$WORK/model.tflite" >/dev/null
check "a refusal exits 2, so a sweep records it and carries on" 2 "$(status_of)"

for code in unknown-device not-installed; do
    says "{\"schema\":1,\"ok\":false,\"code\":\"$code\",\"error\":\"no\"}"
    run test model "$WORK/model.tflite" >/dev/null
    check "$code exits 3" 3 "$(status_of)"
done

says '{"schema":1,"ok":false,"code":"failed","error":"something else"}'
run test model "$WORK/model.tflite" >/dev/null
check "anything else that stopped a run exits 1" 1 "$(status_of)"

(
    export DROIDRUNNER_DEVICE_URL="http://127.0.0.1:1"
    run test model "$WORK/model.tflite" >/dev/null
    check "an agent that does not answer exits 4, which is what stops a sweep" 4 "$(status_of)"
)

(
    unset DROIDRUNNER_DEVICE_URL
    run capabilities >/dev/null
    check "no agent URL at all is also 4, not a usage error" 4 "$(status_of)"
)

echo
echo "stdout is the result and nothing else"

says '{"schema":1,"ok":true,"avgUs":12.5}'
out="$(run test model "$WORK/model.tflite")"
check "stdout carries exactly the JSON the agent returned" \
    '{"schema":1,"ok":true,"avgUs":12.5}' "$out"

run test model "$WORK/model.tflite" --output "$WORK/result.json" >/dev/null
check "--output writes the same JSON to the file" \
    '{"schema":1,"ok":true,"avgUs":12.5}' "$(cat "$WORK/result.json")"

capabilities '{"agent":"droidrunner/0.1","nnapi":{"devices":[{"name":"nnapi-reference"}]}}'
run capabilities --output "$WORK/caps.json" >/dev/null
check "--output works for capabilities too, as the usage says it does everywhere" \
    0 "$([ -s "$WORK/caps.json" ] && echo 0 || echo 1)"
contains "and writes what capabilities returned" '"agent":"droidrunner/0.1"' \
    "$(cat "$WORK/caps.json" 2>/dev/null)"

echo
echo "the shapes a consumer parses"

capabilities '{"nnapi":{"devices":[{"name":"mtk-mdla_shim"},{"name":"nnapi-reference"}]}}'
out="$(run devices)"
check "devices lists one driver per line" "mtk-mdla_shim
nnapi-reference" "$out"

run devices --output "$WORK/devices.txt" >/dev/null
check "--output works for devices in its plain form as well" \
    "mtk-mdla_shim
nnapi-reference" "$(cat "$WORK/devices.txt" 2>/dev/null)"

out="$(run devices --json)"
check "devices --json returns the array shape" \
    '{"schema":1,"ok":true,"devices":["mtk-mdla_shim","nnapi-reference"]}' "$out"

says '{"schema":1,"ok":true,"avgUs":172.3,"gflops":27.38}'
run bench-all --iterations 1 --size 8 >/dev/null
check "bench-all exits 0 after printing its table" 0 "$(status_of)"

out="$(run bench-all --iterations 1 --size 8 --json)"
contains "bench-all --json returns a results array" '"results":[' "$out"
check "bench-all --json exits 0" 0 "$(status_of)"

echo
echo "batch, which is what a sweep sends"

printf '[{"id":"a","path":"/home/runner/a.tflite","iterations":0}]' > "$WORK/manifest.json"
run test batch "$WORK/manifest.json" --budget-ms 1234 >/dev/null
check "a manifest is accepted" 0 "$(status_of)"
contains "the manifest is forwarded under models" '"models":[' "$(sent)"
contains "--budget-ms reaches the agent" '"budgetMs":1234' "$(sent)"

printf '{"not":"an array"}' > "$WORK/bad.json"
run test batch "$WORK/bad.json" >/dev/null
check "a manifest that is not an array is refused before anything is sent" 1 "$(status_of)"

echo
echo "misuse"

run >/dev/null
check "no subcommand prints usage and fails" 1 "$(status_of)"
contains "the usage it prints names the commands" "droidrunner-device capabilities" \
    "$(cat "$WORK/stderr")"

run nonsense >/dev/null
check "an unknown command fails" 1 "$(status_of)"

run devices --nonsense >/dev/null
check "an unknown option fails rather than being ignored" 1 "$(status_of)"

echo
printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
