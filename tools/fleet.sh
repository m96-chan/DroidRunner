#!/usr/bin/env bash
#
# What every phone in the pool is doing, in one place (issue #7).
#
# The issue sketched two options and recommended the cheap one: a read-only
# view joining GitHub's runner state with what the devices say about
# themselves, and no new infrastructure. This is that.
#
# Two facts make it more than `gh api ... | jq`:
#
#   A phone does not necessarily serve this repository. Which one it serves is
#   a property of the phone, read out of its own .runner, so the repositories
#   to ask about are discovered rather than configured — the same reason
#   roll-fleet.sh had to learn it before it could avoid killing a running job.
#
#   A phone can be attached and not registered, or registered and not
#   attached, and those are different problems. Both are shown, and the second
#   is the one nobody notices: a runner GitHub thinks is offline may be a phone
#   on somebody's desk that nothing has told you about.
#
# Usage: tools/fleet.sh [--json]
set -euo pipefail

PACKAGE=io.github.m96chan.droidrunner
REPO="${DROIDRUNNER_REPO:-m96-chan/DroidRunner}"

as_json=""
[ "${1:-}" = "--json" ] && as_json=1

command -v gh >/dev/null || { echo "fleet: gh is required" >&2; exit 1; }

# --- what is plugged in ------------------------------------------------------
serials=""
if command -v adb >/dev/null; then
    serials="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
fi

# --- which repositories to ask about -----------------------------------------
# Every phone that will say, plus $REPO, so a fleet lent entirely to another
# project still reports this one as empty rather than not reporting it.
repos="$REPO"
for serial in $serials; do
    url="$(adb -s "$serial" shell run-as "$PACKAGE" \
        cat files/runner-runtime/home/runner/.runner 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*"gitHubUrl": *"https:\/\/github.com\/\([^"]*\)".*/\1/p' \
        || true)"
    [ -n "$url" ] && repos="$repos $url"
done
repos="$(echo "$repos" | tr ' ' '\n' | grep -v '^$' | sort -u)"

# --- what GitHub thinks ------------------------------------------------------
registry="$(mktemp)"; trap 'rm -f "$registry"' EXIT
for r in $repos; do
    gh api "repos/$r/actions/runners" \
        -q ".runners[]|[\"$r\", .name, .status, (.busy|tostring), ([.labels[].name]|join(\",\"))]|@tsv" \
        2>/dev/null >> "$registry" || true
done

# --- what each attached phone says about itself ------------------------------
attached="$(mktemp)"; trap 'rm -f "$registry" "$attached"' EXIT
for serial in $serials; do
    model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    version="$(adb -s "$serial" shell dumpsys package "$PACKAGE" 2>/dev/null \
        | sed -n 's/ *versionName=//p' | head -1 | tr -d '\r')"
    debuggable=no
    adb -s "$serial" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -q DEBUGGABLE && debuggable=yes
    battery="$(adb -s "$serial" shell dumpsys battery 2>/dev/null \
        | sed -n 's/ *level: //p' | head -1 | tr -d '\r')"
    plugged=no
    adb -s "$serial" shell dumpsys battery 2>/dev/null | grep -qE '(AC|USB|Wireless) powered: true' && plugged=yes
    thermal="$(adb -s "$serial" shell dumpsys thermalservice 2>/dev/null \
        | sed -n 's/.*Thermal Status: //p' | head -1 | tr -d '\r')"
    runtime="$(adb -s "$serial" shell run-as "$PACKAGE" \
        cat files/runner-runtime/.installed 2>/dev/null | tr -d '\r' || true)"
    serving="$(adb -s "$serial" shell run-as "$PACKAGE" \
        cat files/runner-runtime/home/runner/.runner 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*"agentName": *"\([^"]*\)".*/\1/p' || true)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$serial" "${model:-?}" "${version:-not installed}" "$debuggable" \
        "${battery:-?}" "$plugged" "${thermal:-?}" "${runtime:-?}" "${serving:-}" >> "$attached"
done

if [ -n "$as_json" ]; then
    python3 - "$registry" "$attached" <<'PY'
import json, sys
reg = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
att = [l.rstrip("\n").split("\t") for l in open(sys.argv[2]) if l.strip()]
by_name = {r[1]: r for r in reg}
print(json.dumps({
    "schema": 1,
    "runners": [
        {"repository": r[0], "name": r[1], "status": r[2],
         "busy": r[3] == "true", "labels": r[4].split(",") if r[4] else []}
        for r in reg
    ],
    "attached": [
        {"serial": a[0], "model": a[1], "appVersion": a[2],
         "debuggable": a[3] == "yes", "batteryPercent": a[4], "charging": a[5] == "yes",
         "thermalStatus": a[6], "runtime": a[7],
         # `registeredAs: null` means asked and registered to nothing. A
         # release build refuses run-as and cannot be asked at all, which is a
         # different fact and gets a different key — reporting the first for
         # the second is what this whole view exists to stop doing to phones.
         **({"registeredAs": a[8] or None,
             "seenByGitHub": a[8] in by_name if a[8] else False}
            if a[3] == "yes" else {"registrationReadable": False})}
        for a in att
    ],
}, indent=2))
PY
    exit 0
fi

printf '%-26s %-8s %-5s %-13s %s\n' RUNNER STATUS BUSY REPOSITORY LABELS
while IFS=$'\t' read -r repo name status busy labels; do
    [ -n "$name" ] || continue
    printf '%-26s %-8s %-5s %-13s %s\n' \
        "$name" "$status" "$busy" "${repo#*/}" "$(echo "$labels" | tr ',' ' ' | cut -c1-46)"
done < "$registry"

echo
printf '%-14s %-12s %-18s %-8s %-8s %s\n' SERIAL MODEL APP BATTERY THERMAL RUNTIME
while IFS=$'\t' read -r serial model version debuggable battery plugged thermal runtime serving; do
    [ -n "$serial" ] || continue
    mark=""
    [ "$debuggable" = no ] && mark=" (release)"
    [ "$plugged" = yes ] && power="${battery}%⚡" || power="${battery}%"
    printf '%-14s %-12s %-18s %-8s %-8s %s\n' \
        "$(echo "$serial" | cut -c1-13)" "$model" "$version$mark" "$power" "$thermal" "$runtime"
    # A phone that is plugged in and registered, which GitHub has never heard
    # of, is the case this view exists to make visible.
    if [ "$debuggable" = no ]; then
        printf '  %s runs a release build, so it cannot be asked what it serves\n' "$model"
    elif [ -n "$serving" ] && ! cut -f2 "$registry" | grep -qx "$serving"; then
        printf '  %s is registered as %s, and no repository here reports it\n' "$model" "$serving"
    fi
done < "$attached"
