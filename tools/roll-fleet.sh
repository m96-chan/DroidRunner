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
repos=""
for serial in $devices; do
    # `|| true` because a release build refuses run-as and pipefail would
    # otherwise make an unreadable phone abort the whole roll. A phone that
    # will not say which repository it serves is one this cannot check, not
    # one worth stopping for — $REPO below is still asked either way.
    url="$(adb -s "$serial" shell run-as "$PACKAGE" \
        cat files/runner-runtime/home/runner/.runner 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*"gitHubUrl": *"https:\/\/github.com\/\([^"]*\)".*/\1/p' \
        || true)"
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

rolled=""
for serial in $devices; do
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
    rolled="$rolled $model"
done

command -v gh >/dev/null || { note "no gh; not waiting for the runners"; exit 0; }

# Reinstalling orphans the listener's session, and GitHub holds it for up to a
# minute or so (#79). Coming back is the normal case, not a fault.
note "waiting for the runners to come back"
for _ in $(seq 1 30); do
    # Across every repository the phones say they serve, for the same reason
    # the busy check is: asking only $REPO waits forever for a phone that was
    # lent to another project and will never appear in this one's registry.
    online=""
    for r in $repos; do
        online="$online
$(gh api "repos/$r/actions/runners" -q '.runners[]|select(.status=="online")|.name' 2>/dev/null || true)"
    done
    missing=""
    for model in $rolled; do
        printf '%s\n' "$online" | grep -q -- "$model" || missing="$missing $model"
    done
    [ -n "$missing" ] || { note "online:$rolled"; exit 0; }
    sleep 10
done
die "still waiting on:$missing (looked in: $(echo $repos | tr '\n' ' '))"
