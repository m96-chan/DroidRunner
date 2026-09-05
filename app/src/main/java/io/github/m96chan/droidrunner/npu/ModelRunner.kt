package io.github.m96chan.droidrunner.npu

import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a caller-supplied TFLite model (issue #4).
 *
 * The built-in benchmarks prove a driver executes *something*; this answers
 * the question a device pool exists for — how does *my* model behave on this
 * silicon. Input is filled deterministically rather than left uninitialised,
 * so latency does not depend on whatever happened to be in memory and two
 * runs of the same model are comparable.
 *
 * Pinning to an accelerator is best-effort by design: NNAPI silently falls
 * back to CPU when a driver cannot take a graph, so the result reports which
 * device was requested and lets the caller compare against the CPU baseline
 * rather than claiming an acceleration that may not have happened.
 */
internal object ModelRunner {

    /**
     * Returns every buffer to the start before an invocation.
     *
     * A run leaves each buffer at its limit, so reusing one without this makes
     * the next bulk copy throw. This is separate, and tested, because getting
     * it wrong is not obvious from the failure: the exception names
     * `ByteBuffer.put` and nothing about the reason, and it appears on every
     * driver at once — which reads like a delegate problem and is not one.
     */
    internal fun rewindAll(inputs: Array<Any>, outputs: Map<Int, ByteBuffer>) {
        inputs.forEach { (it as ByteBuffer).rewind() }
        outputs.values.forEach { it.rewind() }
    }

    /**
     * [inputs] replaces the fixed fill pattern, one file per input tensor, and
     * [outputDir] receives the outputs — both raw little-endian in the tensor's
     * own dtype. With neither, this behaves exactly as it did: a latency job
     * must not change because a correctness feature was added beside it (#92).
     */
    fun run(
        model: File,
        deviceName: String?,
        iterations: Int,
        warmup: Int = 2,
        inputs: List<File> = emptyList(),
        outputTarget: TensorIo.Target? = null,
        /** Also run the model with no delegate, in this same request (#93). */
        baseline: Boolean = false,
        diagnosticsDir: File? = null,
        /** Samples what the phone was doing, at each end of the loop (#98). */
        conditions: (() -> DeviceConditions)? = null,
        /** Keep every iteration, in the order it ran (#98). */
        keepTimings: Boolean = false,
        /** Return what the delegate printed, verbatim (#128). */
        keepDelegateLog: Boolean = false,
        /** Let the GPU delegate drop to fp16, which it will not do unasked. */
        allowFp16: Boolean = false,
    ): String {
        // Zero is a real answer, not a mistake: "was this graph accepted" is
        // complete once tensors are allocated, and perhaps half of a sweep asks
        // nothing else (#94). Timing it anyway is what turns a sweep that fits
        // in a CI job into one that needs its own evening.
        val runs = iterations.coerceIn(0, 500)
        var delegate: AutoCloseable? = null
        var interpreter: Interpreter? = null
        return try {
            val options = Interpreter.Options()
            if (deviceName == GPU_DEVICE) {
                // Precision is a declared choice here, which is the whole point
                // of having this path: the Hexagon computes f32 in fp16 without
                // saying so (tools/ulp), and the GPU is asked instead of
                // discovered. Full precision unless a caller says otherwise.
                delegate = GpuDelegate(
                    GpuDelegate.Options().setPrecisionLossAllowed(allowFp16),
                ).also { options.addDelegate(it) }
            } else if (deviceName != null) {
                delegate = NnApiDelegate(
                    NnApiDelegate.Options()
                        .setAcceleratorName(deviceName)
                        .setUseNnapiCpu(false)
                        .setAllowFp16(false),
                ).also { options.addDelegate(it) }
            }
            // The interpreter states its partitioning while it is being built,
            // and the NNAPI delegate names the operators it refuses. Neither
            // has an API; printing is the only source (#93).
            val log = diagnosticsDir?.let { File(it, "tflite-build.log") }
            val built = if (log != null) {
                OutputCapture.around(log) { Interpreter(model, options) }
            } else {
                Interpreter(model, options) to ""
            }
            interpreter = built.first
            val delegation = Delegation.parse(built.second)
            // Tensor sizes are only final once allocation has run — and with a
            // delegate attached they can differ from the pre-allocation values,
            // which is how the first attempt ended up sizing every buffer wrong.
            interpreter.allocateTensors()

            val inputSpecs = specsOf(interpreter, input = true)
            TensorIo.mismatch(inputSpecs, inputs)?.let { throw TensorIo.Mismatch(it) }

            val buffers = inputSpecs.map { spec ->
                ByteBuffer.allocateDirect(spec.bytes).order(ByteOrder.nativeOrder()).apply {
                    if (inputs.isEmpty()) {
                        // A fixed pattern keeps runs comparable without pulling
                        // in whatever the allocator handed us.
                        while (remaining() > 0) put((position() % 251).toByte())
                        rewind()
                    } else {
                        TensorIo.load(this, inputs[spec.index])
                    }
                }
            }.toTypedArray<Any>()
            val outputs = (0 until interpreter.outputTensorCount).associate { index ->
                index to ByteBuffer
                    .allocateDirect(interpreter.getOutputTensor(index).numBytes())
                    .order(ByteOrder.nativeOrder())
            }

            // Every buffer has to be rewound before every run, warmup included:
            // an invocation leaves each one at its limit, and the next bulk
            // copy into a buffer with nothing remaining throws. The timing
            // loop did this and the warmup did not, which is why the first
            // run always worked and the second never did.
            fun invoke() {
                rewindAll(buffers, outputs)
                interpreter.runForMultipleInputsOutputs(buffers, outputs)
            }

            if (runs > 0) {
                repeat(warmup) { invoke() }
            }
            val before = conditions?.invoke()
            val measured = LongArray(runs)
            repeat(runs) { run ->
                val started = System.nanoTime()
                invoke()
                measured[run] = System.nanoTime() - started
            }
            val after = conditions?.invoke()
            // Sorted separately: the run order is the whole value of the raw
            // timings, since a throttle is visible as drift across the loop and
            // in nothing else. Sorting in place would have thrown that away.
            val timings = measured.copyOf().apply { sort() }

            JSONObject()
                .put("ok", true)
                .put("model", model.name)
                .put("sizeBytes", model.length())
                .put("requestedDevice", deviceName ?: "default")
                // What actually happened, rather than what was asked for.
                .put("executed", executedFor(delegation, deviceName).first)
                // Names both halves when there are two: the delegate that
                // claimed the nodes and the driver it was pinned to.
                .put("executedBy", executedFor(delegation, deviceName).second)
                .apply {
                    // What the delegate said, unparsed. Our reading of it is
                    // a regex over prose and the prose is not an API — so the
                    // evidence travels with the conclusion, and a caller who
                    // disagrees with us can say why. Included whenever the
                    // attribution failed, because that is when it is needed and
                    // when nobody thought to ask for it in advance (#128).
                    val unattributed = delegation == null && deviceName != null
                    if (keepDelegateLog || unattributed) {
                        put("delegateLog", built.second.takeLast(MAX_LOG_CHARS))
                    }
                    delegation?.let {
                        put(
                            "delegation",
                            JSONObject()
                                .put("delegated", it.delegated)
                                .put("total", it.total)
                                .put("partitions", it.partitions)
                                .put("delegate", it.delegate ?: JSONObject.NULL)
                                .put("describe", it.describe())
                                .put("partial", it.partial),
                        )
                    }
                    // The one thing about a delegate that comes from an API
                    // rather than from prose (#128). It says the delegate hit
                    // an error, not which operator — but unlike the log text,
                    // a TFLite upgrade cannot silently reword it.
                    (delegate as? NnApiDelegate)?.let { attached ->
                        runCatching {
                            if (attached.hasErrors()) {
                                put("nnapiErrno", attached.getNnapiErrno())
                            }
                        }
                    }
                    if (deviceName == GPU_DEVICE) put("precisionLossAllowed", allowFp16)
                }
                .put("iterations", runs)
                .apply {
                    if (runs > 0) {
                        put("avgUs", timings.average() / 1000.0)
                        put("medianUs", timings[runs / 2] / 1000.0)
                        put("minUs", timings.first() / 1000.0)
                        put("maxUs", timings.last() / 1000.0)
                        // The tail is where a throttle shows and the median
                        // does not (#98).
                        put("p90Us", Timings.percentile(timings, 90) / 1000.0)
                        put("p99Us", Timings.percentile(timings, 99) / 1000.0)
                        if (keepTimings) {
                            put("timingsUs", JSONArray(measured.map { it / 1000.0 }))
                        }
                    }
                    DeviceConditions.describe(before, after)?.let { put("conditions", it) }
                }
                .put("inputs", JSONArray(inputSpecs.map(TensorIo::describe)))
                .put(
                    "outputs",
                    JSONArray(specsOf(interpreter, input = false).map(TensorIo::describe)),
                )
                .apply {
                    // Run here rather than in a second workflow step, so the
                    // ratio comes from one thermal state instead of two
                    // minutes apart (#93).
                    if (baseline && deviceName != null) {
                        put(
                            "baseline",
                            JSONObject(
                                run(
                                    model, null, runs, warmup, inputs, null, false, null,
                                    conditions, keepTimings,
                                ),
                            ),
                        )
                    }
                    // Written after the timing loop, from the last invocation:
                    // saving on every iteration would measure the filesystem.
                    outputTarget?.let { target ->
                        put(
                            "outputFiles",
                            JSONArray(
                                specsOf(interpreter, input = false).map { spec ->
                                    TensorIo.save(target, spec, outputs.getValue(spec.index))
                                },
                            ),
                        )
                    }
                }
                .toString()
        } catch (failure: Throwable) {
            // Buffer-size failures carry no message, so report the shapes the
            // interpreter actually settled on — guessing at them from outside
            // cost two device round trips.
            // A file no interpreter can build is not a statement about any
            // driver, and a sweep should stop rather than record it 61 more
            // times (#128 follow-up, reported by the first outside user).
            val code = when {
                // The caller's bytes did not fit the model's tensors: their
                // file and their byte count, so their code.
                failure is TensorIo.Mismatch -> ResultContract.Code.INVALID_REQUEST
                interpreter == null && modelIsUnloadable(model) ->
                    ResultContract.Code.INVALID_MODEL
                else -> ResultContract.Code.FAILED
            }
            ResultContract.failure(
                code = code,
                // Ours, and a sentence. This field held the exception's class
                // name until #138, which is not one.
                error = when (code) {
                    ResultContract.Code.INVALID_REQUEST ->
                        "the inputs given do not fit this model"
                    ResultContract.Code.INVALID_MODEL ->
                        "no interpreter could be built from this file, " +
                            "with or without a delegate"
                    else -> "the run failed on ${deviceName ?: "the CPU"}"
                },
                // Theirs, verbatim: the half that names the tensors.
                message = failure.message,
                at = failure.stackTrace.firstOrNull()?.toString(),
            )
                .put("model", model.name)
                .put("requestedDevice", deviceName ?: "default")
                .apply {
                    interpreter?.let { live ->
                        runCatching {
                            put("inputBytes", (0 until live.inputTensorCount)
                                .joinToString(",") { live.getInputTensor(it).numBytes().toString() })
                            put("outputBytes", (0 until live.outputTensorCount)
                                .joinToString(",") { live.getOutputTensor(it).numBytes().toString() })
                        }
                    }
                }
                .toString()
        } finally {
            runCatching { interpreter?.close() }
            runCatching { delegate?.close() }
        }
    }

    /**
     * How much of the delegate's own output travels back with a result.
     *
     * Enough to hold the partitioning line and what surrounds it, and not so
     * much that a 62-row sweep carries a megabyte of it.
     */
    private const val MAX_LOG_CHARS = 4000

    /** What the interpreter settled on, which is only final after allocation. */
    private fun specsOf(interpreter: Interpreter, input: Boolean): List<TensorIo.Spec> {
        val count = if (input) interpreter.inputTensorCount else interpreter.outputTensorCount
        return (0 until count).map { index ->
            val tensor =
                if (input) interpreter.getInputTensor(index) else interpreter.getOutputTensor(index)
            TensorIo.Spec(
                index = index,
                name = tensor.name(),
                type = tensor.dataType().name,
                shape = tensor.shape().toList(),
                bytes = tensor.numBytes(),
                scale = tensor.quantizationParams()?.scale,
                zeroPoint = tensor.quantizationParams()?.zeroPoint,
            )
        }
    }

}
