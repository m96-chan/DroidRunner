#!/usr/bin/env bash
#
# Fixtures for tools/check-workflow-run-blocks.sh, in both directions.
#
# The gate is a required check, so a false positive costs more than the gap it
# closes: it blocks a correct pull request, and the answer to a check that
# cries wolf is to stop believing it. It did exactly that — a job-level
# `defaults: run: shell: bash` is inherited by every step under it, and the
# scanner only ever asked what the step itself wrote (#243).
#
# So the passing cases matter as much as the failing ones, and both are here.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
GATE=tools/check-workflow-run-blocks.sh
FIXTURES=tools/tests/fixtures/workflow-gate

passed=0
failed=0

# Runs the gate over one fixture and says whether it accepted it.
#
# An absolute path, because the gate cds to the repository root itself — a
# relative one would quietly scan the real workflows and pass every time,
# which is how the first version of this file reported four green lies.
ROOT="$PWD"
verdict() {  # verdict <fixture>
    if "$GATE" "$ROOT/$FIXTURES/$1/.github/workflows" >/dev/null 2>&1; then
        echo accepts
    else
        echo rejects
    fi
}

check() {  # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        echo "  ok   $1"
        passed=$((passed + 1))
    else
        echo "  FAIL $1"
        echo "       expected: $2"
        echo "       got:      $3"
        failed=$((failed + 1))
    fi
}

echo "a correct workflow is not blocked"
for f in step-shell quoted-shell inherits-job-default inherits-workflow-default; do
    check "$f" accepts "$(verdict "$f")"
done

echo
echo "and the two shapes it exists for are still caught"
check "no-shell" rejects "$(verdict no-shell)"
check "expression-in-run" rejects "$(verdict expression-in-run)"
# A default written under one job does not reach the next one.
check "second-job-loses-default" rejects "$(verdict second-job-loses-default)"

echo
echo "the composite action is in scope"
# Scanned by default rather than only under .github/workflows: it is the file
# a consumer outside this repository runs, and its `run:` blocks take their
# values from that consumer's inputs (#243).
# Run with no argument, so the gate chooses its own roots — that choice is the
# part under test. It resolves them from its own location, so it has to sit
# inside the tree being scanned; copying it there is the only way to exercise
# the real default rather than a path handed to it.
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
cp -r "$FIXTURES/action-expression/.github" "$FIXTURES/action-expression/actions" "$sandbox/"
mkdir -p "$sandbox/tools"
cp "$GATE" "$sandbox/tools/"
if (cd "$sandbox" && ./tools/check-workflow-run-blocks.sh >/dev/null 2>&1); then
    check "an expression in an action.yml run: block" rejects accepts
else
    check "an expression in an action.yml run: block" rejects rejects
fi
# And the same tree with the expression removed is accepted, so the rejection
# above is about the expression and not about the copying.
sed -i 's/echo "\${{ inputs.whatever }}"/echo hello/' "$sandbox/actions/thing/action.yml"
if (cd "$sandbox" && ./tools/check-workflow-run-blocks.sh >/dev/null 2>&1); then
    check "a clean action.yml is accepted" accepts accepts
else
    check "a clean action.yml is accepted" accepts rejects
fi

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
