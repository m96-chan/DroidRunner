#!/usr/bin/env bash
#
# Fails when a workflow hands an expression to a shell, or throws away the
# exit code of a pipeline (issues #203 and #204).
#
# `${{ }}` is pasted into a `run:` block as text before any shell sees it, so
# the value is code and not data. `git check-ref-format` permits `'`, `;`, `$`
# and backticks in a tag name, and `tag='${{ github.ref_name }}'` in the
# release job hands all of them to a job that has already written the signing
# keystore to disk and exported every signing secret. The same shape carries a
# `workflow_dispatch` input onto a self-hosted phone. An `env:` entry and
# `"$TAG"` leaves the value as data, which is the only version of this that
# stays correct whatever somebody names a tag.
#
# The other half is quieter. GitHub's default shell is `bash -e {0}`:
# `-o pipefail` arrives only when a step writes `shell: bash` itself. Without
# it a pipeline reports the status of its *last* command, so
# `compare.py … | tee` was green however the operator matrix compared, and
# `openssl dgst … | base64 > f` published a zero-byte signature when the key
# was mangled — base64 succeeds on empty input. Both were gates that could not
# fail, and both were invisible to everything except a person reading the log.
#
# So this reads every `run:` block under .github/workflows/ and fails on
# either shape, because the next one is otherwise found the same way: after it
# has already let something through.
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Given a directory, scan that (which is how the fixtures are driven).
# Given nothing, scan what this repository actually ships: the workflows, and
# the composite action — which was outside the net until #243 and is the one
# file a consumer outside this repository runs, with `run:` blocks that take
# their values from that consumer's inputs.
ROOTS=()
if [ $# -gt 0 ]; then
    [ -d "$1" ] || die "no workflow directory at $1"
    ROOTS=("$1")
else
    [ -d .github/workflows ] || die "no workflow directory at .github/workflows"
    ROOTS=(.github/workflows)
    for d in actions/*/; do
        [ -d "$d" ] && ROOTS+=("${d%/}")
    done
fi

command -v awk >/dev/null || die "awk is required"

# YAML read as lines rather than parsed: what matters here is which physical
# line an operator has to go and look at, and a parser hands back a block
# scalar with its line numbers already thrown away.
#
# The scanner tracks block scalars (`run: |` and every other `key: |`, so a
# `path:` list is never mistaken for script), and remembers whether the step
# containing a `run:` declared `shell: bash` — which may be written after the
# `run:`, so a step is only judged once it ends.
scan() {
    awk '
    # Nothing is deleted: entries past pend are never read again, and mawk is
    # what awk is on a GitHub runner, so the program stays inside POSIX.
    function reset() { has_shell = 0; pend = 0; piped = 0 }

    # A step inherits `defaults: run: shell:` from its job and from the
    # workflow, so the question is never only what the step itself wrote.
    # Asking only that failed a correct workflow, which in a required check is
    # worse than the gap it closes, because the answer to a check that
    # cries wolf is to stop believing it (#243).
    function shelled() { return has_shell || job_shell || wf_shell }

    function flush(   i) {
        for (i = 1; i <= pend; i++) {
            if (ptype[i] == "expr")
                printf "expr\t%s\t%d\n", file, pline[i]
            else if (!shelled())
                printf "pipe\t%s\t%d\n", file, pline[i]
        }
        reset()
    }

    # YAML lets the value be written plain or quoted, either way, and all of
    # them mean bash.
    function unquoted(v) {
        if (v ~ /^".*"$/ || v ~ /^'\''.*'\''$/)
            return substr(v, 2, length(v) - 2)
        return v
    }

    # A shell line with its comment and its quoted strings taken out, so the
    # `|` in `grep -iE '\''a|b'\''` is not read as a pipeline and the one in a
    # comment explaining a pipeline is not either.
    function bare(s,   i, n, c, prev, q, out, depth, stack, top) {
        n = length(s); q = ""; out = ""; depth = 0; stack = ""
        for (i = 1; i <= n; i++) {
            c = substr(s, i, 1)
            prev = (i > 1) ? substr(s, i - 1, 1) : ""

            # `$(` opens a fresh, unquoted context even inside "…", because the
            # shell runs what is in there. Without this the `|` in
            # `n="$(a | b)"` was stripped with the quotes and the pipeline was
            # never seen — three real ones in device-model.yml went unflagged,
            # and the identical unquoted form was caught, which made the gate
            # look like it had nothing left to find. Single quotes are literal
            # all the way down, so `$(` inside them opens nothing.
            if (q != "'\''" && c == "$" && substr(s, i + 1, 1) == "(") {
                stack = (q == "" ? "-" : q) stack
                q = ""; depth++; i++
                out = out " "
                continue
            }
            if (depth > 0 && q == "" && c == ")") {
                top = substr(stack, 1, 1); stack = substr(stack, 2)
                q = (top == "-" ? "" : top); depth--
                out = out " "
                continue
            }

            if (q != "") {
                if (q == "\"" && c == "\\") { i++; continue }
                if (c == q) q = ""
                continue
            }
            if (c == "\\") { i++; continue }
            if (c == "\"" || c == "'\''") { q = c; continue }
            # A `#` inside a substitution is still a comment, but one inside
            # quotes is not, and `depth` does not change that either way.
            if (c == "#" && (i == 1 || prev == " " || prev == "\t")) break
            out = out c
        }
        return out
    }

    function has_pipe(s,   i, n, c) {
        n = length(s)
        for (i = 1; i <= n; i++) {
            c = substr(s, i, 1)
            if (c != "|") continue
            if (substr(s, i + 1, 1) == "|") { i++; continue }  # ||, not a pipe
            return 1
        }
        return 0
    }

    function script(s, ln) {
        if (index(s, "${{") > 0) { pend++; ptype[pend] = "expr"; pline[pend] = ln }
        if (!piped && has_pipe(bare(s))) {
            piped = 1; pend++; ptype[pend] = "pipe"; pline[pend] = ln
        }
    }

    FNR == 1 {
        flush(); in_scalar = 0; file = FILENAME
        wf_shell = 0; job_shell = 0
        def_ind = -1; in_def_run = 0; def_run_ind = -1
        in_jobs = 0; job_ind = -1
    }

    {
        blank = ($0 ~ /^[ \t]*$/)
        match($0, /^ */); ind = RLENGTH

        if (in_scalar) {
            if (blank || ind > scalar_ind) {
                if (scalar_run) script($0, FNR)
                next
            }
            in_scalar = 0
        }
        if (blank) next

        t = $0
        sub(/^[ ]*/, "", t)
        item = (t ~ /^-[ \t]+/)
        if (item) sub(/^-[ \t]+/, "", t)
        keyind = length($0) - length(t)

        # A new list item ends the step before it, and with it the chance for
        # a `shell: bash` to turn up.
        if (item) flush()

        if (!match(t, /^[A-Za-z_][A-Za-z0-9_.-]*:/)) next
        key = substr(t, 1, RLENGTH - 1)
        val = substr(t, RLENGTH + 1)
        sub(/^[ \t]+/, "", val); sub(/[ \t]+$/, "", val)

        # Left the block a `defaults:` (or its `run:`) opened.
        if (def_ind >= 0 && keyind <= def_ind) { def_ind = -1; in_def_run = 0 }
        if (in_def_run && keyind <= def_run_ind) in_def_run = 0

        # A key at the indent job names sit at is the next job, and a job-level
        # default does not carry into it.
        if (key == "jobs" && keyind == 0) { in_jobs = 1; job_ind = -1 }
        else if (in_jobs && !item && keyind > 0) {
            if (job_ind < 0) job_ind = keyind
            if (keyind == job_ind) { job_shell = 0; def_ind = -1; in_def_run = 0 }
        }

        if (key == "defaults") { def_ind = keyind; in_def_run = 0; next }
        if (def_ind >= 0 && key == "run" && keyind > def_ind) {
            in_def_run = 1; def_run_ind = keyind; next
        }

        if (key == "shell" && unquoted(val) == "bash") {
            if (in_def_run) {
                # Indent 0 is the `defaults:` the whole workflow carries;
                # anything deeper belongs to the job it is written under.
                if (def_ind == 0) wf_shell = 1; else job_shell = 1
            } else has_shell = 1
            next
        }

        if (val ~ /^[|>][-+]?[0-9]*$/) {
            in_scalar = 1; scalar_ind = keyind; scalar_run = (key == "run")
            next
        }
        if (key == "run" && val != "") script(val, FNR)
    }

    END { flush() }
    ' "$@"
}

failed=0
report() {  # report <type> <file> <line>
    failed=1
    echo "  FAIL $2:$3" >&2
    if [ "$1" = "expr" ]; then
        echo "       a \`run:\` block interpolates \`\${{ }}\`, so whatever the" >&2
        echo "       expression evaluates to is pasted in as shell source." >&2
        echo "       Put it in the step's \`env:\` and reference \"\$NAME\"." >&2
    else
        echo "       a \`run:\` block pipes, and the step does not declare" >&2
        echo "       \`shell: bash\`. GitHub's default is \`bash -e {0}\` with no" >&2
        echo "       pipefail, so only the last command's exit code survives." >&2
        echo "       Add \`shell: bash\` to the step." >&2
    fi
}

files=()
for root in "${ROOTS[@]}"; do
    for f in "$root"/*.yml "$root"/*.yaml; do
        [ -f "$f" ] && files+=("$f")
    done
done
[ ${#files[@]} -gt 0 ] || die "no workflows found under ${ROOTS[*]}"

while IFS=$'\t' read -r type file line; do
    [ -n "${type:-}" ] || continue
    report "$type" "$file" "$line"
done < <(scan "${files[@]}")

if [ "$failed" -ne 0 ]; then
    exit 1
fi

for f in "${files[@]}"; do
    echo "  ok   $f" >&2
done
echo "every run: block takes its values as data and keeps its exit codes" >&2
