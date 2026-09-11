#!/usr/bin/env bash
#
# Puts the build in this working tree on every attached phone (issue #127).
#
# Verifying anything on hardware means: build, install on each phone, relaunch,
# wait for the runners to come back. Done by hand it is four steps times
# however many phones are plugged in, and the failure is quiet — miss one and
# it answers the next job with the old build. That is not hypothetical: a phone
# running a build that predated #111 reported `nnapi-reference` as accelerating
# 60 of 62 operators, and working out why meant comparing the shape of its
# answers against another phone's.
#
# Usage: tools/roll-fleet.sh [--skip-build]
set -euo pipefail

PACKAGE=io.github.m96chan.droidrunner
APK=app/build/outputs/apk/debug/app-debug.apk
REPO="${DROIDRUNNER_REPO:-m96-chan/DroidRunner}"

die() { echo "roll-fleet: $*" >&2; exit 1; }
note() { echo "$*" >&2; }

# --- waiting for the runners -------------------------------------------------
#
# Functions, and up here, so tools/tests/test-roll-fleet.sh can drive the loop
# against a fixture registry with no phone attached: sourcing this script with
# DROIDRUNNER_ROLL_FLEET_LIB set defines them and stops just below. This loop is
# the part worth a test, because a wrong answer from it is the silent failure
# the whole script exists to remove.

# Every runner GitHub currently calls online, one name per line, across all the
# repositories the phones say they serve — for the same reason the busy check
# asks all of them: asking only $REPO waits forever for a phone that was lent
# to another project and will never appear in this one's registry.
registry_names() {
    local r
    for r in $repos; do
        gh api "repos/$r/actions/runners" \
            -q '.runners[]|select(.status=="online")|.name' 2>/dev/null || true
    done
}

# Waits for every name given to show up there, and fails naming the ones that
# never did. Reinstalling orphans the listener's session and GitHub holds it for
# up to a minute or so (#79), so coming back late is the normal case; not coming
# back at all is the fault this reports.
wait_for_runners() {
    local names name missing online
    names=("$@")
    note "waiting for the runners to come back"
    for _ in $(seq 1 30); do
        online="$(registry_names)"
        missing=()
        for name in "${names[@]}"; do
            # -F because a runner name is not a regular expression, and -x
            # because it is regularly a prefix of another one: a fleet with a
            # "Pixel 7" and a "Pixel 7 Pro" had the Pro's registry entry answer
            # for the Pixel 7, so the roll exited 0 with a phone still running
            # the old APK (#207).
            grep -Fqx -- "$name" <<<"$online" || missing+=("$name")
        done
        if [ "${#missing[@]}" -eq 0 ]; then
            note "online: ${names[*]}"
            return 0
        fi
        sleep 10
    done
    die "still waiting on: ${missing[*]} (looked in: $(echo $repos | tr '\n' ' '))"
}

[ -z "${DROIDRUNNER_ROLL_FLEET_LIB:-}" ] || return 0

[ -f gradlew ] || die "run this from the repository root"

# A signed release on a test phone is how a signature mismatch strands a
# registration: Android refuses the upgrade, and reinstalling discards the
# runner registration and the stored GitHub credentials. The fleet runs
# 0.0.0-dev and keeps doing so.
[ -z "${DROIDRUNNER_RELEASE_TAG:-}" ] || die \
    "DROIDRUNNER_RELEASE_TAG is set — this rolls debug builds only"

command -v adb >/dev/null || die "adb is required"
devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
[ -n "$devices" ] || die "no phone is attached over USB"

# Which repository a phone serves is a property of the phone, not of this
# working tree: a device registered elsewhere still dies when its APK is
# replaced. Asking only $REPO looked safe while every phone served it, and
# stopped being true the moment one was lent to another project.
#
# Serials go in an array and everything downstream is keyed by one, because a
# serial never contains a space and Build.MODEL usually does.
repos=""
serials=()
registered=()
for serial in $devices; do
    # One read of .runner for the two things only the phone knows: which
    # repository it serves, and the name it registered under.
    #
    # `|| true` because a release build refuses run-as and pipefail would
    # otherwise make an unreadable phone abort the whole roll. A phone that
    # will not say which repository it serves is one this cannot check, not
    # one worth stopping for — $REPO below is still asked either way.
    runner="$(adb -s "$serial" shell run-as "$PACKAGE" \
        cat files/runner-runtime/home/runner/.runner 2>/dev/null | tr -d '\r' || true)"
    url="$(printf '%s\n' "$runner" \
        | sed -n 's/.*"gitHubUrl": *"https:\/\/github.com\/\([^"]*\)".*/\1/p' | head -n1)"
    # The registered name is read whole rather than rebuilt out of
    # ro.product.model. SetupScreen.kt registers `android-${Build.MODEL}-$id`,
    # and that id is the app's ANDROID_ID — derived from the signing key, so
    # not the value adb reads back — which leaves the phone's own .runner as
    # the only place off GitHub holding the exact string the registry knows.
    serials+=("$serial")
    registered+=("$(printf '%s\n' "$runner" \
        | sed -n 's/.*"agentName": *"\([^"]*\)".*/\1/p' | head -n1)")
    case " $repos " in *" ${url:-} "*) ;; *) repos="$repos ${url:-}" ;; esac
done
repos="$(echo "${repos:-} $REPO" | tr ' ' '\n' | grep -v '^$' | sort -u)"

# Replacing the APK kills the process group with a signal nothing catches, and
# a job running on that phone dies with it. Checked before anything is built.
if command -v gh >/dev/null; then
    for r in $repos; do
        busy="$(gh api "repos/$r/actions/runners" \
            -q '[.runners[]|select(.busy==true)|.name]|join(", ")' 2>/dev/null || echo "")"
        [ -z "$busy" ] || die "these runners are running a job right now ($r): $busy"
    done
fi

commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
dirty=""
git diff --quiet 2>/dev/null || dirty=" +uncommitted changes"
note "rolling $commit$dirty"

if [ "${1:-}" != "--skip-build" ]; then
    note "building"
    ./gradlew -q assembleDebug
fi
[ -f "$APK" ] || die "no debug APK at $APK"
want="$(sha256sum "$APK" | cut -d' ' -f1)"

waiting=()
for i in "${!serials[@]}"; do
    serial="${serials[$i]}"
    model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

    # A phone already carrying a signed release is not part of this. Android
    # refuses a debug APK over it, so the install fails and takes the whole
    # roll down with it — and forcing it through would discard the runner
    # registration and the stored credentials. It is also the only device
    # running what users actually install, which is worth keeping.
    if adb -s "$serial" shell pm path "$PACKAGE" 2>/dev/null | grep -q package: &&
       ! adb -s "$serial" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -q DEBUGGABLE
    then
        note "== $model ($serial): release build installed, left alone"
        continue
    fi

    note "== $model ($serial)"
    adb -s "$serial" install -r "$APK" >/dev/null || die "install failed on $model"

    # Proof the phone holds this build, rather than a version string that says
    # 0.0.0-dev on every build ever made (#124).
    remote="$(adb -s "$serial" shell "sha256sum $(adb -s "$serial" shell pm path "$PACKAGE" \
        | tr -d '\r' | sed 's/^package://' | head -1)" 2>/dev/null | cut -d' ' -f1 | tr -d '\r')"
    if [ "$remote" = "$want" ]; then
        note "   installed, and the APK on the phone is the one just built"
    else
        note "   WARNING: the APK on the phone hashes to ${remote:-nothing}, wanted $want"
    fi

    adb -s "$serial" shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1 || true
    if [ -n "${registered[$i]}" ]; then
        waiting+=("${registered[$i]}")
    else
        # A phone that has not registered yet took nothing away by restarting,
        # and will never appear in any registry, so waiting for it would only
        # spend five minutes before failing a roll that worked.
        note "   not registered with any repository, so no runner to wait for"
    fi
done

command -v gh >/dev/null || { note "no gh; not waiting for the runners"; exit 0; }
[ "${#waiting[@]}" -gt 0 ] || { note "nothing rolled is a registered runner"; exit 0; }

wait_for_runners "${waiting[@]}"
