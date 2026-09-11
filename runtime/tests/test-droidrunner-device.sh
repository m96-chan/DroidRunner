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
trap 'rm -rf "$WORK" "${TALLY:-}"; [ -n "${AGENT_PID:-}" ] && kill "$AGENT_PID" 2>/dev/null' EXIT

# Counted in a file, not in variables. Half of this suite runs inside ( )
# subshells so that an exported variable cannot leak into the next case — and a
# subshell's increments never reach the parent, so a FAIL in one printed its
# complaint and was then left out of the tally. A suite that can print FAIL and
# still say "0 failed" is a suite that agrees with itself.
# Cleaned up by the trap above, which is extended rather than replaced: a
# second `trap ... EXIT` silently discards the first, and the first is what
# kills the stub agent. The suite hung on exactly that while this was written.
TALLY="$(mktemp)"
tally() { printf '%s\n' "$1" >> "$TALLY"; }

# Test names are sentences, and the failure prints what it wanted beside what
# it got — a bare "assertion failed" in a shell test is a second debugging
# session.
check() {  # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        tally ok
        printf '  ok   %s\n' "$1"
    else
        tally fail
        printf '  FAIL %s\n       wanted: %s\n       got:    %s\n' "$1" "$2" "$3"
    fi
}

contains() {  # contains <name> <needle> <haystack>
    case "$3" in
        *"$2"*) tally ok; printf '  ok   %s\n' "$1" ;;
        *) tally fail
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
echo '[{"path":"/home/runner/m.tflite"}]' > "$WORK/manifest.json"
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

says '{"schema":1,"ok":false,"code":"invalid-model","error":"Cannot create interpreter"}'
run test model "$WORK/model.tflite" >/dev/null
check "a file no interpreter can load exits 1, so a sweep stops rather than recording it 61 times" \
    1 "$(status_of)"

says '{"schema":1,"ok":false,"code":"failed","error":"something else"}'
run test model "$WORK/model.tflite" >/dev/null
check "anything else that stopped a run exits 1" 1 "$(status_of)"

(
    export DROIDRUNNER_DEVICE_URL="http://127.0.0.1:1"
    run test model "$WORK/model.tflite" >/dev/null
    check "an agent that does not answer exits 4, which is what stops a sweep" 4 "$(status_of)"
)

# Every command, not just the one that had a test. `ask` exits on an
# unreachable agent, but an `exit` inside $( ) ends only the subshell — so
# every command that inlined it reported success having fetched nothing, and
# `devices --json` answered `{"ok":true,"devices":[]}`, which reads as "this
# phone has no accelerators" rather than "nobody asked it" (#171).
(
    export DROIDRUNNER_DEVICE_URL="http://127.0.0.1:1"
    for form in "capabilities" "devices" "devices --json" "devices --all" \
                "devices --all --json" "bench-all --json"; do
        run $form >/dev/null
        check "$form exits 4 when nothing answers" 4 "$(status_of)"
        check "$form prints nothing when nothing answers" "" "$(run $form 2>/dev/null)"
    done

    run test batch "$WORK/manifest.json" >/dev/null
    check "test batch exits 4 when nothing answers" 4 "$(status_of)"

    # --output is the form a job uses to keep a result, and a file that exists
    # is a file somebody will read.
    rm -f "$WORK/unreachable.json"
    run capabilities --output "$WORK/unreachable.json" >/dev/null
    check "an unreachable agent writes no --output file" "no" \
        "$([ -f "$WORK/unreachable.json" ] && echo yes || echo no)"
)

# The other half: a phone that answers and exposes nothing is a fact, and must
# not be turned into a transport failure by the fix above.
capabilities '{"nnapi":{"devices":[]},"accepts":[]}'
run devices >/dev/null
check "an agent that answers with no drivers still exits 0" 0 "$(status_of)"
check "and prints an empty list rather than failing" "" "$(run devices 2>/dev/null)"
check "the JSON form says so explicitly" \
    '{"schema":1,"ok":true,"devices":[]}' "$(run devices --json)"

(
    unset DROIDRUNNER_DEVICE_URL
    run capabilities >/dev/null
    check "no agent URL at all is also 4, not a usage error" 4 "$(status_of)"
)

echo
echo "an HTTP error the agent answers with"

# -f hides the body, so ask() asks again without it — and that retry was a
# hard-coded POST, so a GET arrived at a path the agent does not route as one,
# came back 404 with curl exiting 0, and the error envelope was handed on as
# the payload. `devices --json` then said {"ok":true,"devices":[]} and exited
# 0, which reads as "this phone has no accelerators" and was in fact a token
# that had rotated (#205). Nothing here could catch it while the stub answered
# every GET with 200.
for code in 401 403; do
    printf '%s' "$code" > "$WORK/get-status"
    for form in "capabilities" "devices" "devices --json" "devices --all" \
                "devices --all --json" "bench-all --json"; do
        run $form >/dev/null
        check "$form exits 1 on HTTP $code, the status invalid-request publishes" \
            1 "$(status_of)"
        contains "$form names the status on HTTP $code" "$code" "$(cat "$WORK/stderr")"
        check "$form prints no result on HTTP $code" "" "$(run $form 2>/dev/null)"
    done
    check "devices --json does not answer HTTP $code with an empty fleet" \
        "" "$(run devices --json 2>/dev/null)"
    rm -f "$WORK/http-$code.json"
    run capabilities --output "$WORK/http-$code.json" >/dev/null
    check "HTTP $code writes no --output file either" "no" \
        "$([ -f "$WORK/http-$code.json" ] && echo yes || echo no)"
done
rm -f "$WORK/get-status"

# A POST is not the same story, and the fix must not make it one: the agent
# answers 400 with the contract's own code and message about the request that
# was sent, and that body is the result a consumer keeps with --output.
says '{"schema":1,"ok":false,"code":"unknown-device","error":"no such device"}'
printf '400' > "$WORK/status"
run test model "$WORK/model.tflite" --device nnapi-reference >/dev/null
check "a POST answered 400 still exits on the code in the body" 3 "$(status_of)"
check "and that body still reaches stdout, because it is the result" \
    '{"schema":1,"ok":false,"code":"unknown-device","error":"no such device"}' \
    "$(run test model "$WORK/model.tflite" --device nnapi-reference 2>/dev/null)"
rm -f "$WORK/status"

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

# `devices` is the NNAPI drivers and always was; a consumer parsing it should
# not have that change underneath them. `--all` is the superset, and the reason
# it exists is that enumerating accelerators from `devices` misses the GPU —
# which on a Snapdragon is the only one NNAPI cannot reach at all (#158).
capabilities '{"nnapi":{"devices":[{"name":"nnapi-reference"}]},"accepts":["nnapi-reference","gpu","qnn-gpu","qnn-htp"]}'

# --feature names an experiment the agent would otherwise refuse. Repeatable,
# and absent from the body when nobody asked — so a request that does not use
# it is byte-for-byte the request it was before (#159).
says '{"schema":1,"ok":true,"avgUs":1.0}'
run test model "$WORK/model.tflite" --device gpu >/dev/null
check "no --feature means no features field at all" 0 \
    "$(grep -c '"features"' "$WORK/last-request.json" 2>/dev/null || true)"
run test model "$WORK/model.tflite" --device 'mtk-neuron_shim+gpu' \
    --feature multi-delegate >/dev/null
check "--feature travels as an array" '"features":["multi-delegate"]' \
    "$(grep -o '"features":\[[^]]*\]' "$WORK/last-request.json" 2>/dev/null)"

check "devices still lists only what NNAPI exposes" "nnapi-reference" "$(run devices)"
check "devices --all lists every value --device accepts" "nnapi-reference
gpu
qnn-gpu
qnn-htp" "$(run devices --all)"
check "devices --all --json returns the same array shape" \
    '{"schema":1,"ok":true,"devices":["nnapi-reference","gpu","qnn-gpu","qnn-htp"]}' \
    "$(run devices --all --json)"

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
echo "option values that would rewrite the request"

# The request body is built by concatenation, so a value carrying a comma or a
# quote writes fields of its own: --iterations '1,"device":"qnn-htp"' moved the
# benchmark onto the Hexagon while the caller read the numbers as the default
# driver's, and actions/run-model forwards that input verbatim from a consumer's
# workflow_dispatch (#206). Refusing it by name, before anything is sent, is the
# only answer that does not publish a measurement of something nobody asked for.
rejects() {  # rejects <name> <option it must name> <args...>
    local name="$1" option="$2"; shift 2
    rm -f "$WORK/last-request.json"
    run "$@" >/dev/null
    check "$name exits 1, the status invalid-request publishes" 1 "$(status_of)"
    check "$name sends no request at all" "no" \
        "$([ -f "$WORK/last-request.json" ] && echo yes || echo no)"
    contains "$name says which option it refused" "$option" "$(cat "$WORK/stderr")"
}

says '{"schema":1,"ok":true,"avgUs":1.0}'
rejects "a non-numeric --iterations" --iterations \
    test model "$WORK/model.tflite" --iterations abc
rejects "an --iterations carrying a comma" --iterations \
    test model "$WORK/model.tflite" --iterations '1,"device":"qnn-htp"'
rejects "a --device carrying a quote" --device \
    test model "$WORK/model.tflite" --device 'a","iterations":9999'
rejects "a --feature carrying a quote" --feature \
    test model "$WORK/model.tflite" --feature 'x","baseline":true'
rejects "a --size that is not a number" --size \
    test conv --size '8,"device":"qnn-htp"'
rejects "a bench-all --iterations carrying a comma" --iterations \
    bench-all --iterations '1,"device":"qnn-htp"'
rejects "a --budget-ms that is not a number" --budget-ms \
    test batch "$WORK/manifest.json" --budget-ms '1,"models":[]'

# And the values that are real still travel: the + in a multi-delegate device
# name, the - in a feature, and the 0 iterations that mean load it and delegate
# it but time nothing.
rm -f "$WORK/last-request.json"
run test model "$WORK/model.tflite" --device 'mtk-neuron_shim+gpu' \
    --feature multi-delegate --iterations 0 >/dev/null
check "a device name carrying + and _ is still accepted" 0 "$(status_of)"
contains "and travels with the iterations asked for" '"iterations":0' "$(sent)"

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
passed="$(grep -c '^ok$' "$TALLY" || true)"
failed="$(grep -c '^fail$' "$TALLY" || true)"
printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
