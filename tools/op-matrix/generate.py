#!/usr/bin/env python3
"""Single-operator TFLite models, one per (operator, precision) — issue #96.

A whole network cannot say which operator a driver refused. One rejected node
pushes its entire partition back to the CPU, so EfficientNet-Lite0 falling to
the CPU on MediaTek's MDLA proves the vendor table wrong without saying what it
should have said instead. A model containing exactly one operator has no such
ambiguity: it was taken or it was not.

These are built with TensorFlow's own converter rather than assembled by hand.
The temptation is to write the flatbuffers directly and drop the dependency,
but a hand-quantized graph a driver rejects because its scales are invalid is
indistinguishable, from the outside, from an operator the driver does not
support — which is precisely the wrong answer this issue exists to stop us
publishing. So the models come from the converter everyone else's models come
from, and every one is loaded back and checked to contain the single operator
it claims before it is written out.

Usage: generate.py --out DIR [--only OP,OP]
"""
import argparse
import json
import os
import pathlib
import sys

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
import numpy as np
import tensorflow as tf

SPATIAL = (1, 16, 16, 8)
FLAT = (1, 64)


def const(*shape, seed=0):
    """Weights that are the same on every machine that runs this.

    A matrix built from models that differ run to run cannot be compared with
    the one published last month, which is most of the point of having one.
    """
    return tf.constant(
        np.random.default_rng(seed).standard_normal(shape).astype("float32") * 0.1
    )


# (tflite operator, input shapes, the graph). One operator each: anything the
# converter turns into two nodes is dropped below rather than guessed at.
OPS = {
    "ADD": ([SPATIAL, SPATIAL], lambda a, b: a + b),
    "SUB": ([SPATIAL, SPATIAL], lambda a, b: a - b),
    "MUL": ([SPATIAL, SPATIAL], lambda a, b: a * b),
    "MAXIMUM": ([SPATIAL, SPATIAL], tf.maximum),
    "MINIMUM": ([SPATIAL, SPATIAL], tf.minimum),
    "SQUARED_DIFFERENCE": ([SPATIAL, SPATIAL], tf.math.squared_difference),
    "CONV_2D": ([SPATIAL], lambda x: tf.nn.conv2d(
        x, const(3, 3, 8, 16, seed=1), strides=1, padding="SAME")),
    "DEPTHWISE_CONV_2D": ([SPATIAL], lambda x: tf.nn.depthwise_conv2d(
        x, const(3, 3, 8, 1, seed=2), strides=[1, 1, 1, 1], padding="SAME")),
    "TRANSPOSE_CONV": ([SPATIAL], lambda x: tf.nn.conv2d_transpose(
        x, const(3, 3, 8, 8, seed=3), output_shape=[1, 32, 32, 8],
        strides=2, padding="SAME")),
    # With no bias, NNAPI rejects the operation outright
    # (ANEURALNETWORKS_BAD_DATA) on every driver of every phone tried — a model
    # it cannot represent rather than an operator anything refused. The bias is
    # fused into the one node.
    "FULLY_CONNECTED": ([FLAT], lambda x: tf.matmul(x, const(64, 32, seed=4))
                        + const(32, seed=5)),
    "AVERAGE_POOL_2D": ([SPATIAL], lambda x: tf.nn.avg_pool2d(x, 2, 2, "VALID")),
    "MAX_POOL_2D": ([SPATIAL], lambda x: tf.nn.max_pool2d(x, 2, 2, "VALID")),
    "MEAN": ([SPATIAL], lambda x: tf.reduce_mean(x, axis=[1, 2], keepdims=True)),
    "SUM": ([SPATIAL], lambda x: tf.reduce_sum(x, axis=[1, 2], keepdims=True)),
    "SOFTMAX": ([FLAT], tf.nn.softmax),
    "LOGISTIC": ([SPATIAL], tf.sigmoid),
    "TANH": ([SPATIAL], tf.tanh),
    "RELU": ([SPATIAL], tf.nn.relu),
    "RELU6": ([SPATIAL], tf.nn.relu6),
    "LEAKY_RELU": ([SPATIAL], lambda x: tf.nn.leaky_relu(x, alpha=0.1)),
    "HARD_SWISH": ([SPATIAL], lambda x: x * tf.nn.relu6(x + 3.0) * (1.0 / 6.0)),
    "CONCATENATION": ([SPATIAL, SPATIAL], lambda a, b: tf.concat([a, b], axis=3)),
    "RESHAPE": ([SPATIAL], lambda x: tf.reshape(x, (1, -1))),
    "PAD": ([SPATIAL], lambda x: tf.pad(x, [[0, 0], [1, 1], [1, 1], [0, 0]])),
    "TRANSPOSE": ([SPATIAL], lambda x: tf.transpose(x, [0, 3, 1, 2])),
    "RESIZE_BILINEAR": ([SPATIAL], lambda x: tf.image.resize(x, (32, 32), "bilinear")),
    "RESIZE_NEAREST_NEIGHBOR": ([SPATIAL], lambda x: tf.image.resize(x, (32, 32), "nearest")),
    "STRIDED_SLICE": ([SPATIAL], lambda x: x[:, ::2, ::2, :]),
    "SLICE": ([SPATIAL], lambda x: tf.slice(x, [0, 2, 2, 0], [1, 8, 8, 8])),
    "SPACE_TO_DEPTH": ([SPATIAL], lambda x: tf.nn.space_to_depth(x, 2)),
    "DEPTH_TO_SPACE": ([SPATIAL], lambda x: tf.nn.depth_to_space(x, 2)),
}

PRECISIONS = ("float32", "int8")


def representative(shapes):
    def dataset():
        rng = np.random.default_rng(7)
        for _ in range(16):
            yield [rng.standard_normal(s).astype("float32") for s in shapes]
    return dataset


def convert(shapes, build, precision):
    fn = tf.function(build).get_concrete_function(
        *[tf.TensorSpec(s, tf.float32) for s in shapes]
    )
    converter = tf.lite.TFLiteConverter.from_concrete_functions([fn])
    if precision == "int8":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.representative_dataset = representative(shapes)
        # Refuse a float fallback rather than accept one: a graph that quietly
        # keeps a float kernel is not the int8 row it would be filed under.
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter.inference_input_type = tf.int8
        converter.inference_output_type = tf.int8
    return converter.convert()


def operators_in(blob):
    """What the converter actually emitted, read back from the model itself.

    Without the resolver argument this reports the *delegated* graph: the host
    interpreter applies XNNPACK while allocating, and every model then appears
    to contain a node called DELEGATE. Reading a delegated graph as though it
    were the model is the same mistake, one layer down, that #93 was about.
    """
    interpreter = tf.lite.Interpreter(
        model_content=blob,
        experimental_op_resolver_type=(
            tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
        ),
    )
    interpreter.allocate_tensors()
    return [op["op_name"] for op in interpreter._get_ops_details()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default="", help="comma-separated operator names")
    args = parser.parse_args()

    wanted = [o.strip().upper() for o in args.only.split(",") if o.strip()] or list(OPS)
    unknown = [o for o in wanted if o not in OPS]
    if unknown:
        sys.exit(f"unknown operator(s): {', '.join(unknown)}")

    out = pathlib.Path(args.out)
    # Flat, and the index names files relative to this directory: the whole
    # directory is one artifact, and the device job joins its download path to
    # what the index says. A subdirectory here becomes models/models/ there.
    out.mkdir(parents=True, exist_ok=True)

    generated, skipped = [], []
    for operator in wanted:
        shapes, build = OPS[operator]
        for precision in PRECISIONS:
            name = f"{operator.lower()}-{precision}"
            try:
                blob = convert(shapes, build, precision)
                emitted = operators_in(blob)
            except Exception as failure:  # the converter is the authority
                skipped.append({"id": name, "operator": operator,
                                "precision": precision,
                                "reason": f"the converter would not produce it: "
                                          f"{type(failure).__name__}: {failure}"[:300]})
                continue
            # Anything but one node of the expected kind is unusable here: a
            # refusal could then belong to either node, which is the very
            # ambiguity these models exist to remove.
            if emitted != [operator]:
                skipped.append({"id": name, "operator": operator,
                                "precision": precision,
                                "reason": "the converter emitted "
                                          f"{emitted or 'nothing'}, not one {operator}"})
                continue
            path = out / f"{name}.tflite"
            path.write_bytes(blob)
            generated.append({"id": name, "operator": operator, "precision": precision,
                              "file": f"{name}.tflite", "bytes": len(blob),
                              "inputShapes": [list(s) for s in shapes]})

    (out / "models.json").write_text(json.dumps(
        {"schema": 1, "generatedBy": f"tensorflow {tf.__version__}",
         "models": generated, "skipped": skipped}, indent=2) + "\n")
    # A tab-separated index beside it, because the job that reads this runs in
    # the guest, and the guest has neither python3 nor jq.
    with (out / "models.index").open("w") as index:
        for model in generated:
            index.write(f"{model['id']}\t{model['file']}\t"
                        f"{model['operator']}\t{model['precision']}\n")

    print(f"{len(generated)} models, {len(skipped)} skipped", file=sys.stderr)
    for entry in skipped:
        print(f"  skipped {entry['id']}: {entry['reason']}", file=sys.stderr)


if __name__ == "__main__":
    main()
