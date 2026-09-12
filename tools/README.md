# tools

Things that run on a computer rather than on a phone.

## The operator matrix (`op-matrix/`)

Which operators a phone's drivers actually take, asked of the silicon. The
three scripts are three separate jobs on purpose, because they need three
different machines:

| | runs where | does |
| --- | --- | --- |
| `generate.py` | a hosted runner, with TensorFlow | builds one model per operator per precision — 62 of them — and checks each holds the single operator it claims |
| `reduce.py` | a hosted runner | turns the device's sweep into `matrix.md` and `matrix.json` |
| `compare.py` | a hosted runner | compares a matrix against the one committed for that phone, and fails on a regression |
| `baseline-path.py` | either | says where a given phone's committed matrix lives |
| `index.py` | either | writes `docs/matrices/README.md`'s table of committed matrices, and with `--check` fails when it has drifted |

The phone itself only runs the sweep, in bash and curl: **the guest has neither
python3 nor jq**, checked against the published bundle's dpkg status rather than
assumed.

`test_reduce.py`, `test_compare.py` and `test_index.py` have no dependencies and
run in CI on every push, alongside `index.py --check`. Every mistake in that
reduction reads as a statement about somebody's silicon, which is why they are
not left to the machine with TensorFlow on it.

Run the tests with:

```sh
python3 -m unittest discover -s tools/op-matrix
```
## `fleet.sh` — what every phone in the pool is doing

```
tools/fleet.sh [--json]
```

Joins GitHub's runner state with what the attached devices say about
themselves. No new infrastructure: it is the read-only half of
[#7](https://github.com/m96-chan/DroidRunner/issues/7), which is the half that
issue recommended starting with.

Two things it knows that `gh api` alone does not.

**Which repositories to ask about are discovered, not configured.** A phone
does not necessarily serve this one; which it serves is read out of its own
`.runner`. A fleet lent entirely to another project would otherwise show as
empty, and `roll-fleet.sh` had to learn the same thing before it could avoid
killing a running job.

**Attached and registered are different facts, and so is not being able to
ask.** A release build refuses `run-as`, so its registration cannot be read at
all — reported as `registrationReadable: false` rather than as
`registeredAs: null`, because "we could not ask" and "it is registered to
nothing" are not the same answer.


## `check-tflite-wording.sh`

Everything this project says about who ran a graph is a regex over prose TFLite
prints while applying a delegate. There is no API for it. This reads the format
strings out of the library **actually being packaged** and fails when the one
`Delegation.parse` is written against is gone — so a TFLite upgrade that rewords
it turns a build red, instead of turning every result into `executed: unknown`
with nothing failing anywhere.

Runs on every build. See [#128](https://github.com/m96-chan/DroidRunner/issues/128).

## `check-action-pin.sh`

The tutorial is a copy-paste page, so the ref it names for `actions/run-model`
is the one that ends up in someone else's workflow. It sat on `@v0.7.0` for
seven releases: a tag pins the action, not the document, and that one has
neither the `stable` nor the `p90-us` output the next section tells the reader
to branch on. Nothing failed — the workflow simply could not do what the page
said.

This takes the pin from the READMEs, which is the one the project stands behind,
and fails when any page on the site names a different one.

Runs on every push. See [#215](https://github.com/m96-chan/DroidRunner/issues/215).

## `check-workflow-run-blocks.sh`

Nothing compiles a workflow, and the two ways one goes wrong both read as
normal YAML.

`${{ }}` inside a `run:` block is substituted before any shell sees it, so the
value is source and not an argument: `git check-ref-format` permits `'`, `;`
and a backtick in a tag name, and the release job has the signing keystore on
disk while it builds one. The fix is an `env:` entry and `"$TAG"`.

And GitHub's default shell is `bash -e {0}` — `-o pipefail` arrives only when a
step writes `shell: bash` itself. Without it a pipeline reports its *last*
command's status, which is how `compare.py … | tee` stayed green through a
regression and `openssl dgst … | base64` published a zero-byte signature.

It reads the two `defaults: run: shell:` scopes as well as each step, because
a step inherits them — asking only what the step wrote failed a correct
workflow, and a required check that cries wolf gets ignored rather than fixed
([#243](https://github.com/m96-chan/DroidRunner/issues/243)). It scans
`actions/*/action.yml` too: that is the file a consumer outside this
repository runs, and its `run:` blocks take their values from that consumer's
inputs.

`tools/tests/test-check-workflow-run-blocks.sh` drives it over fixtures in
both directions — four workflows it must accept, three it must reject, and a
composite action either way.

Runs on every push. See [#203](https://github.com/m96-chan/DroidRunner/issues/203)
and [#204](https://github.com/m96-chan/DroidRunner/issues/204).

## `ulp/`

Does an accelerator compute what it was asked to, or only run it? Nothing in
this project asked until an outside consumer found f32 results deviating on a
Hexagon. `make-add-f32.py` builds four fixture sets, `compare.py` reports the
difference in ULP, and `resolution.py` finds the finest step a device still
resolves.

The fixtures are committed rather than generated in CI: 12 KB, deterministic,
and the device job has no Python. The full result, and the two traps it cost to
get there, are in [`ulp/README.md`](ulp/README.md).

## `roll-fleet.sh`

Builds and installs the working tree on every phone attached over USB, checks the
APK on each is the one just built, and waits for the runners to come back.

Refuses to run while any runner is busy — replacing the APK kills the process
group with a signal nothing catches, and the job dies with it — and refuses a
release tag outright, because the fleet runs `0.0.0-dev` and a signature change
strands a registration.

**A phone can only answer for itself.** Each rolled phone is waited for under
the exact name it registered under, read from its own `.runner`, and matched
whole. A record of `Build.MODEL` strings word-split into fragments, and each
fragment substring-matched other phones' runner names, so a "Pixel 7 Pro" that
came back reported a "Pixel 7" that never did as online and the roll exited 0
with that phone still serving the old APK
([#207](https://github.com/m96-chan/DroidRunner/issues/207)). A phone that does
not come back now fails the roll and is named.

`tests/test-roll-fleet.sh` drives that waiting loop against a fixture registry
— no phone, no network, no five minutes of waiting — and runs in CI on every
push.
