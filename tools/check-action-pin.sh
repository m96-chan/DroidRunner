#!/usr/bin/env bash
#
# The site must pin `actions/run-model` where the READMEs pin it (issue #215).
#
# The tutorial is a copy-paste page: whatever ref it names is the one that ends
# up in somebody else's workflow. It sat on `@v0.7.0` for seven releases, and a
# tag is not a snapshot of a document — it is a snapshot of the action, so that
# pin silently withheld the `stable` and `p90-us` outputs the very next section
# tells the reader to branch on. Nothing failed; the workflow just could not do
# what the page said it would.
#
# Nothing links the HTML to the action, so this is what does: the pin in the
# READMEs is the one the project stands behind, and every pin on the site has
# to be that one.
set -euo pipefail

die() { echo "FAIL: $*" >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ACTION="m96-chan/DroidRunner/actions/run-model"

# Every file that publishes a pin. A new page that shows the action belongs in
# this list on the day it is written, not on the day a reader reports that the
# workflow they copied has no outputs.
sources=(README.md README.ja.md)
targets=(site/tutorial.html site/ja/tutorial.html site/index.html site/ja/index.html)

for path in "${sources[@]}" "${targets[@]}"; do
    [ -f "$path" ] || die "$path is gone; if a page moved, this list has to move with it"
done

# What the pin is, read out of the READMEs rather than written down here, so
# moving off `@main` stays one edit and not a hunt.
pins() { grep -ho "$ACTION@[A-Za-z0-9._/-]*" "$@" | sed 's|.*@||' | sort -u; }

expected="$(pins "${sources[@]}")"
[ -n "$expected" ] || die "no $ACTION pin in ${sources[*]}; this check has nothing to compare against"
[ "$(printf '%s\n' "$expected" | wc -l)" -eq 1 ] \
    || die "the READMEs disagree about the pin: $(printf '%s ' $expected)"
echo "  ok   READMEs pin $ACTION@$expected" >&2

for path in "${targets[@]}"; do
    found="$(pins "$path" || true)"
    if [ -z "$found" ]; then
        echo "  ok   $path shows no pin" >&2
        continue
    fi
    for ref in $found; do
        if [ "$ref" != "$expected" ]; then
            echo "  FAIL $path pins $ACTION@$ref" >&2
            echo "       the READMEs pin @$expected" >&2
            echo "       A reader copies the site verbatim, and an older ref is an" >&2
            echo "       older action. Check which outputs that ref actually has" >&2
            echo "       before deciding which of the two is wrong." >&2
            exit 1
        fi
    done
    echo "  ok   $path pins @$expected" >&2
done

echo "the site pins the action where the READMEs do" >&2
