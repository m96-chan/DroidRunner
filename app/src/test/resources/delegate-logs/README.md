# Delegate logs, captured from phones

Verbatim output of a TFLite delegate, taken off a device with
`--delegate-log` (issue #128) — not written by hand.

That distinction is the point. The fixture these replaced was a line I typed:

    INFO: Replacing 64 out of 64 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 1 partitions

The device prints `VERBOSE:`, not `INFO:`, and prints two of these lines rather
than one. The regex happened to survive both differences; the test proved
nothing about that, because it was reading a string from the same imagination
that wrote the regex.

To add one: run `Device model benchmark` on the phone, take the
`delegateLog` field out of the result, and drop it here with the driver and
precision in the filename.

| file | device | what it shows |
| --- | --- | --- |
| `mtk-dsp_shim-int8.txt` | MT6899, `mtk-dsp_shim`, EfficientNet-Lite0 int8 | Two delegates in one build. NNAPI takes 5 of 64 nodes, XNNPACK then takes 59 of the remaining 62 — so the run is a CPU fallback, and the *last* line is the one that says so. |
