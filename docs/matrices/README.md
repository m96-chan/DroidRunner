# Committed operator matrices

One file per phone, named the way `tools/op-matrix/baseline-path.py` names it.
Each is the output of the **Operator support matrix** workflow, and each is the
baseline the next run of that workflow is compared against — a driver that
stops taking an operator makes the build red (issue #126).

They are committed rather than kept as artifacts for two reasons. A change to
one shows up in a diff a person reads, which is the point. And they are the
tables themselves: NxPU intends to keep only the entries it can verify on
devices it owns, and these are those entries, with the phone, the driver and
the DroidRunner build that produced them attached.

The table below is generated from those files by `tools/op-matrix/index.py`.
Editing it by hand is undone by the next run, and CI is red while the two
disagree.

| file | SoC | drivers |
| --- | --- | --- |
| `google-pixel-10a.json` | Google Tensor G4 stallion | `google-edgetpu`, `nnapi-reference` |
| `nubia-nx769j.json` | QTI SM8650 qcom | `nnapi-reference`, `qnn-htp`, `gpu` |
| `xiaomi-2511fpc34g.json` | Mediatek MT6899 mt6899 | `mtk-dsp_shim`, `mtk-mdla_shim`, `mtk-neuron_shim`, `nnapi-reference` |

To update one after a change that is genuinely the driver's — an OTA, a new
phone — take `matrix.json` from the workflow's `op-matrix` artifact, replace the
file, and run `tools/op-matrix/index.py` to redraw the table above. The diff is
the evidence, so say in the commit message what moved and why it was not a
regression.

What a cell does and does not claim is in
[`docs/OPERATOR-MATRIX.md`](../OPERATOR-MATRIX.md).
