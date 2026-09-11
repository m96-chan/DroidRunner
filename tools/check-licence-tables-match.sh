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

echo "both licence tables list the same components" >&2
