#!/usr/bin/env bash
#
# Tests for the waiting loop in tools/roll-fleet.sh (issue #207).
#
# The loop is what decides whether a roll worked, and when it was wrong it was
# wrong silently: it matched a space-joined record of Build.MODEL strings
# against the registry with an unanchored grep, so a "Pixel 7 Pro" that came
# back answered for a "Pixel 7" that did not, and the script exited 0 leaving a
# phone on the old APK. Nothing in tools/ had a test before this, and this is
# the part where not having one costs a debugging session on hardware.
#
# No phone, no network: roll-fleet.sh is sourced as a library, and `gh` and
# `sleep` are shell functions here, so the real loop runs against a fixture
# registry listing and the thirty ten-second waits take no time.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Sourcing brings the script's `set -e` with it, and a suite that stops at its
# first failing assertion reports one problem per run.
DROIDRUNNER_ROLL_FLEET_LIB=1 . "$HERE/../roll-fleet.sh"
set +e

passed=0
failed=0

# Test names are sentences, and a failure prints what it wanted beside what it
# got — a bare "assertion failed" in a shell test is a second debugging session.
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

absent() {  # absent <name> <needle> <haystack>
    case "$3" in
        *"$2"*) failed=$((failed + 1))
                printf '  FAIL %s\n       expected NOT to contain: %s\n       got: %s\n' "$1" "$2" "$3" ;;
        *) passed=$((passed + 1)); printf '  ok   %s\n' "$1" ;;
    esac
}

# What GitHub answers with. `gh` is asked for one online runner name per line
# and nothing else, so a file of names is the whole fixture.
REGISTRY="$(mktemp)"
trap 'rm -f "$REGISTRY"' EXIT
registry() { printf '%s\n' "$@" > "$REGISTRY"; }
gh() { cat "$REGISTRY"; }
sleep() { :; }

# Read by registry_names; one repository is enough, since which repositories
# get asked is not what is under test here.
repos="owner/fleet"

# A subshell, because the loop ends the script when a phone never comes back,
# and that exit status is exactly what is being asserted. Stderr is where the
# script says everything.
waits_for() {  # waits_for <name>... -> prints its output, exits with its status
    ( wait_for_runners "$@" ) 2>&1
}

PIXEL="android-Pixel 7-def456"
PRO="android-Pixel 7 Pro-abc123"

echo "the check that every rolled phone came back"

registry "$PRO" "$PIXEL"
out="$(waits_for "$PIXEL" "$PRO")"
check "both phones back exits 0" 0 "$?"
contains "and says so, naming them whole" "$PIXEL" "$out"

registry "$PRO"
out="$(waits_for "$PIXEL" "$PRO")"
check "a phone that never came back fails the roll" 1 "$?"
contains "and the failure names it" "still waiting on: $PIXEL" "$out"
absent "and does not blame the phone that did come back" "$PRO" "$out"

# The bug itself: "Pixel 7" is a prefix of "Pixel 7 Pro", so the Pro's entry
# used to satisfy the Pixel 7's wait.
registry "$PRO"
waits_for "$PIXEL" >/dev/null
check "a neighbour's registry entry never satisfies another phone's name" 1 "$?"

registry "$PIXEL"
waits_for "$PRO" >/dev/null
check "and it does not work the other way round either" 1 "$?"

registry "$PIXEL-spare"
waits_for "$PIXEL" >/dev/null
check "a longer name that contains this one is not this one" 1 "$?"

registry ""
waits_for "$PIXEL" >/dev/null
check "an empty registry fails rather than matching everything" 1 "$?"

# -F, not -E: a model name is data. "Pixel.7" would otherwise be answered by
# any phone whose name differs only in that character.
registry "android-PixelX7-def456"
waits_for "android-Pixel.7-def456" >/dev/null
check "a dot in a model name is a dot, not any character" 1 "$?"

registry "$PIXEL"
waits_for "$PIXEL" >/dev/null
check "the phone that came back is matched exactly" 0 "$?"

echo
printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
