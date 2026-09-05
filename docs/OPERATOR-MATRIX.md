# Operator support matrix

Which operators a phone's drivers actually take, at which precision, asked of
the silicon rather than read off a datasheet.

Run it with the **Operator support matrix** workflow, giving the runner label
that selects the phone. It leaves `matrix.md` and `matrix.json` as artifacts,
and prints the table into the run summary.

## Why one operator per model

A whole network cannot answer this. TFLite partitions a graph, and one refused
node pushes its entire partition back to the CPU — so EfficientNet-Lite0
falling to the CPU on MediaTek's MDLA proves a vendor table wrong without
saying a word about *which* operator was wrong. A model containing exactly one
operator has nowhere to hide the answer: the driver took it or it did not.

The models come from TensorFlow's own converter, on a hosted runner, and each
is loaded back and checked to contain the single operator it claims before it
is used. Assembling the flatbuffers by hand would drop that dependency and
introduce a worse problem: a graph rejected because *our* quantization was
invalid looks, from outside, exactly like an operator the driver does not
support. That is the wrong answer this whole exercise exists to stop us
publishing.

## The control column

Every model is also run with **no device at all**. If it does not run on the
CPU either, the model is broken and the row is excluded and marked as excluded
— never filed as an operator some driver refuses. A defect of ours must not
end up in a table other people compile against.

## What a cell says

| Mark | Meaning |
| --- | --- |
| `✓` | the delegate took every node |
| `~` | it took some of them — for a one-operator model, worth reading the JSON |
| `✗` | it took nothing; the CPU ran the model |
| `·` | this phone has no such driver |
| `!` | the run failed for a reason that is neither of those |
| `—` | excluded: the model did not run on the CPU either |

`matrix.json` carries the same cells with the device's own words beside them —
`executedBy`, and `unsupportedOps` where the delegate named what it refused.

## What a cell does not say

- **Nothing about a different shape.** Each operator is measured at one fixed
  shape. Acceleration is routinely conditional on kernel size, channel count,
  stride or rank, and a driver that takes `CONV_2D` here may refuse yours.
- **Nothing about fused patterns.** Drivers match patterns, not just operators.
  An operator refused on its own can still run inside a fusion the driver
  recognises, and one accepted on its own can still be refused in a graph whose
  layout does not suit it. `✓` is not a promise about your network.
- **Nothing about speed.** The sweep runs with `iterations: 0` — it asks only
  whether the graph was accepted, which is complete once tensors are allocated.
  Timing every operator on every driver would turn a two-minute sweep into an
  evening, for numbers that mean little at these shapes.
- **Nothing about a phone you did not run it on.** The table is keyed by SoC
  because that is what determines the drivers, and it is generated per device.

What it does say is the thing a hand-written table cannot: that on this
silicon, this driver, today, this operator at this precision was or was not
taken — with the device's own attribution behind every cell.

## Adding an operator

Add it to `OPS` in `tools/op-matrix/generate.py` as a one-operator graph. If
the converter emits anything but that single operator, the generator drops it
and says so in the matrix rather than guessing which node a refusal belonged
to.
