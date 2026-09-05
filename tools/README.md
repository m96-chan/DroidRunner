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

The phone itself only runs the sweep, in bash and curl: **the guest has neither
python3 nor jq**, checked against the published bundle's dpkg status rather than
assumed.

`test_reduce.py` and `test_compare.py` have no dependencies and run in CI on
every push. Every mistake in that reduction reads as a statement about somebody's
silicon, which is why they are not left to the machine with TensorFlow on it.

Run the tests with:

```sh
python3 -m unittest discover -s tools/op-matrix
```

## `check-tflite-wording.sh`

Everything this project says about who ran a graph is a regex over prose TFLite
prints while applying a delegate. There is no API for it. This reads the format
strings out of the library **actually being packaged** and fails when the one
`Delegation.parse` is written against is gone — so a TFLite upgrade that rewords
it turns a build red, instead of turning every result into `executed: unknown`
with nothing failing anywhere.

Runs on every build. See [#128](https://github.com/m96-chan/DroidRunner/issues/128).

## `roll-fleet.sh`

Builds and installs the working tree on every phone attached over USB, checks the
APK on each is the one just built, and waits for the runners to come back.

Refuses to run while any runner is busy — replacing the APK kills the process
group with a signal nothing catches, and the job dies with it — and refuses a
release tag outright, because the fleet runs `0.0.0-dev` and a signature change
strands a registration.
