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

| file | SoC | drivers |
| --- | --- | --- |
| `xiaomi-2511fpc34g.json` | MediaTek MT6899 | `mtk-dsp_shim`, `mtk-mdla_shim`, `mtk-neuron_shim`, `nnapi-reference` |
| `nubia-nx769j.json` | Qualcomm SM8650 | `qnn-htp`, `nnapi-reference` |
| `google-pixel-10a.json` | Google Tensor G4 | `google-edgetpu`, `nnapi-reference` |

To update one after a change that is genuinely the driver's — an OTA, a new
phone — take `matrix.json` from the workflow's `op-matrix` artifact and replace
the file. The diff is the evidence, so say in the commit message what moved and
why it was not a regression.

What a cell does and does not claim is in
[`docs/OPERATOR-MATRIX.md`](../OPERATOR-MATRIX.md).
