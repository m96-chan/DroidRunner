#!/usr/bin/env bash
#
# Fails when the two READMEs stop listing the same licensed components
# (issue #216).
#
# The licence table is not a description of the project, it is how the source
# obligation is discharged: GPL-2.0 §3 is satisfied by offering the source from
# the same place as the binary, and the table is where a recipient is told what
# we redistribute and where that source is. README.ja.md carries the same table
# for the readers who use it instead of README.md, so a row that lands in one
# file and not the other leaves half the recipients pointed at nothing. That is
# what happened to the runtime bundle's Ubuntu rootfs: it was named in
# README.md alone, and the Japanese reader was told to reconstruct proot from a
# build script while an archive sat beside the binary.
#
# Nothing else catches it. Both files render, both tables look complete, and
# the short one is short in a language the person adding the component may not
# read.
#
# Since #226 the same list also holds a third copy: the About screen in the
# app. The two tables are on GitHub; the screen is what someone who has only
# the APK has, and that person is who the offer is for. It drifted the whole
# way — the tables pointed at the archive published beside the binary while the
# screen still told the reader to rebuild proot from a commit and a patch
# directory — because nothing was comparing it to anything.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

die() { echo "ERROR: $*" >&2; exit 1; }

# One table is a translation of the other, so the rows cannot be compared as
# text — "The runtime bundle's Ubuntu rootfs" and "runtime bundleのUbuntu
# rootfs" are the same obligation. Each component is identified instead by
# something that survives translation, and a row matching nothing here is an
# error rather than a row to skip: a component this script has never been told
# about is exactly the one that goes missing from one file unnoticed.
components=(
    "proot:termux/proot"
    "talloc:talloc\.samba\.org"
    "ubuntu-rootfs:rootfs"
)

# The table under the licence heading, minus its header and separator rows.
# It stops at the next heading, so a table added later in the file cannot be
# read as this one.
rows() {  # rows <file>
    awk '
        /^## / { licence = ($0 ~ /^## (License|ライセンス)$/); n = 0; next }
        !licence { next }
        /^\|/ { if (++n > 2) print }
    ' "$1"
}

# The component cell, which is the first one, trimmed.
component_cells() {
    awk -F'|' '{ cell = $2; gsub(/^[ \t]+|[ \t]+$/, "", cell); print cell }'
}

keys() {  # keys <file>
    local file="$1"
    rows "$file" | component_cells | while IFS= read -r cell; do
        matched=""
        for component in "${components[@]}"; do
            if printf '%s' "$cell" | grep -qE "${component#*:}"; then
                [ -z "$matched" ] || die \
                    "$file: '$cell' matches both $matched and ${component%%:*}; the patterns no longer tell the components apart"
                matched="${component%%:*}"
            fi
        done
        [ -n "$matched" ] || die \
            "$file: no pattern in this script matches '$cell'. Add the component to \`components\` so both READMEs are held to it."
        echo "$matched"
    done | sort
}

for readme in README.md README.ja.md; do
    [ -f "$readme" ] || die "$readme is gone; the licence table has to live somewhere"
done

en="$(keys README.md)"
ja="$(keys README.ja.md)"

[ -n "$en" ] || die "README.md: found no licence table under '## License'"
[ -n "$ja" ] || die "README.ja.md: found no licence table under '## ライセンス'"

if [ "$en" != "$ja" ]; then
    echo "  FAIL the two licence tables list different components" >&2
    comm -3 <(echo "$en") <(echo "$ja") | while IFS= read -r line; do
        case "$line" in
            $'\t'*) echo "       only in README.ja.md: ${line#$'\t'}" >&2 ;;
            *)      echo "       only in README.md:    $line" >&2 ;;
        esac
    done
    echo "       This table is a GPL-2.0 §3 obligation, not a description of" >&2
    echo "       the project. Whatever was added on one side belongs on the" >&2
    echo "       other, with its licence and where its corresponding source is." >&2
    exit 1
fi

echo "$en" | while IFS= read -r key; do
    echo "  ok   $key is listed in both READMEs" >&2
done

##
## The third copy: the About screen (issue #226).
##
## Listing the component is not enough here. Every row a presence check would
## have looked for was already on the screen throughout #226 — proot, talloc
## and the rootfs each had a line. What was missing was where each one's source
## is, which is the whole of what §3 asks. So a row carries the pointers that
## must appear in the file alongside it, and a row that owes nothing says so.
about="app/src/main/java/io/github/m96chan/droidrunner/ui/AboutPanel.kt"
[ -f "$about" ] || die "$about is gone; the offer still has to reach someone holding only the APK"

# key:label pattern:pointers. The label is the Field under the panel's
# "licences" heading. The screen is not a translation of either table, so it is
# matched to the same keys rather than to the tables themselves. Pointers are
# space-separated and all of them are required: talloc's source is in the same
# archive as proot's, and the rootfs's offer is two files inside the bundle.
about_rows=(
    'proot:^proot$:droidrunner-.*-source\.tar\.gz'
    'talloc:^talloc$:droidrunner-.*-source\.tar\.gz'
    'ubuntu-rootfs:^rootfs$:PACKAGES\.txt SOURCE-OFFER\.txt'
    # Two rows the tables do not carry and that owe nothing: the app's own
    # source is this repository, linked from the panel, and the actions runner
    # is MIT. Declared anyway, so that an unrecognised row is still an error.
    'droidrunner-app:^app$:'
    'actions-runner:^runner$:'
)

# The licence rows on the screen: the Fields under the "licences" heading, up
# to where that block ends. The panel's other Fields are version and device
# information, not a source offer, and must not be read as one.
about_labels() {
    awk '
        /Text\("licences"/ { licences = 1; next }
        !licences { next }
        /Field\("/ {
            if (match($0, /Field\("[^"]*"/)) print substr($0, RSTART + 7, RLENGTH - 8)
            next
        }
        /Spacer\(/ { next }
        /^[[:space:]]*$/ { next }
        { exit }
    ' "$about"
}

about_keys="$(
    about_labels | while IFS= read -r label; do
        matched=""
        for row in "${about_rows[@]}"; do
            rest="${row#*:}"
            if printf '%s' "$label" | grep -qE "${rest%%:*}"; then
                [ -z "$matched" ] || die \
                    "$about: licence row '$label' matches both $matched and ${row%%:*}; the patterns no longer tell the components apart"
                matched="${row%%:*}"
            fi
        done
        [ -n "$matched" ] || die \
            "$about: no pattern in this script matches the licence row '$label'. Add it to \`about_rows\` with the pointers to its source, or with none if it owes none."
        echo "$matched"
    done
)"

[ -n "$about_keys" ] || die \
    "$about: found no licence rows under the \"licences\" heading; if the panel moved, this check has to move with it"

about_failed=""
for key in $en; do
    row=""
    for candidate in "${about_rows[@]}"; do
        case "$candidate" in "$key:"*) row="$candidate" ;; esac
    done
    [ -n "$row" ] || die \
        "the READMEs list $key but \`about_rows\` says nothing about it; add the row the About screen must carry for it"

    key_failed=""
    if ! printf '%s\n' "$about_keys" | grep -qx "$key"; then
        echo "  FAIL $key is in the licence table but not on the About screen" >&2
        key_failed=1
    else
        pointers="${row#*:}"
        pointers="${pointers#*:}"
        # Unquoted on purpose: several patterns, all of which must be there.
        for pattern in $pointers; do
            if ! grep -qE "$pattern" "$about"; then
                echo "  FAIL the About screen lists $key without saying where its source is ($pattern)" >&2
                key_failed=1
            fi
        done
    fi
    if [ -n "$key_failed" ]; then
        about_failed=1
    else
        echo "  ok   $key is offered on the About screen too" >&2
    fi
done

if [ -n "$about_failed" ]; then
    echo "       The About screen is the copy that reaches someone holding only" >&2
    echo "       the APK, and that is who GPL-2.0 §3 owes the source to. The" >&2
    echo "       READMEs are on GitHub; this is not. Offer there whatever the" >&2
    echo "       table offers — $about (issue #226)." >&2
    exit 1
fi

echo "both licence tables and the About screen offer the same components" >&2
