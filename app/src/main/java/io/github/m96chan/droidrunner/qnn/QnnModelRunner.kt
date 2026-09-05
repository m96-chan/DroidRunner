/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 */
package io.github.m96chan.droidrunner.qnn

import io.github.m96chan.droidrunner.npu.DeviceConditions
import io.github.m96chan.droidrunner.npu.Delegation
import io.github.m96chan.droidrunner.npu.modelIsUnloadable
import io.github.m96chan.droidrunner.npu.refuseUnattributable
import io.github.m96chan.droidrunner.npu.ResultContract
import io.github.m96chan.droidrunner.npu.TensorIo
import io.github.m96chan.droidrunner.npu.Timings
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a model on the Hexagon, in the `:qnn` process (issue #82, stage 5).
 *
 * Deliberately not shared with `ModelRunner`, which does the same shape of work
 * for NNAPI. That one runs in the main process beside PRoot and is plain
 * GPL-2.0; this one sits on the other side of a licensing boundary and may not
 * be linked with it. The duplicated buffer setup is the price of the boundary
 * being real rather than asserted.
 *
 * The result is refused unless the delegate says what it executed. Everything
 * else here is measurement; that is the part that makes the measurement mean
 * something.
 */
internal object QnnModelRunner {

    /** What TFLite calls this delegate when it announces the partitioning. */
    private const val QNN_DELEGATE = "TfLiteQnnDelegate"

    /**
     * Where this process says how far it got.
     *
     * A vendor library that segfaults takes the process with it, and there is
     * no reply and no stack. Nor, on a device whose ROM silences logging at the
     * source, any log — see [DelegateLog]. A breadcrumb on disk is what is left
     * to say which step was in progress, and it costs one small write.
     */
    private var breadcrumb: File? = null

    private fun step(name: String) {
        runCatching { breadcrumb?.writeText(name) }
    }

    fun run(
        model: File,
        directory: String,
        libraries: List<String>,
        backend: String,
        iterations: Int,
        warmup: Int = 2,
        inputs: List<File> = emptyList(),
        outputTarget: TensorIo.Target? = null,
        /** Samples what the phone was doing, at each end of the loop (#98). */
        conditions: (() -> DeviceConditions)? = null,
        /** Keep every iteration, in the order it ran (#98). */
        keepTimings: Boolean = false,
    ): String {
        val diagnostics = File(directory).parentFile ?: File(directory)
        breadcrumb = File(diagnostics, "qnn-last-run.txt")
        val log = DelegateLog.fileIn(diagnostics)
        QnnNative.captureOutput(log.path)
        step("starting $backend on ${model.name}")
        // Zero is a real answer, not a mistake: "was this graph accepted" is
        // complete once tensors are allocated, and perhaps half of a sweep asks
        // nothing else (#94). Timing it anyway is what turns a sweep that fits
        // in a CI job into one that needs its own evening.
        val runs = iterations.coerceIn(0, 500)
        step("loading libraries")
        val loading = QnnNative.load(directory, libraries)
        if (!JSONObject(loading).optBoolean("ok")) {
            return failure(model, backend, "QNN did not load", loading)
        }

        val code = QnnOptions.backendCode(backend)
            ?: return failure(model, backend, "'$backend' is not a QNN backend", "")

        // Before the delegate, not after. Qualcomm's own Java wrapper does
        // exactly this — ensureJNILibraryLoaded, then TensorFlowLite.init(),
        // then createDelegate — and the order is the whole of it: a delegate
        // built before the LiteRT runtime exists is created happily and then
        // refuses every graph it is offered, saying nothing about why. That is
        // not written down anywhere; it is in the bytecode of the wrapper this
        // project deliberately does not use.
        step("initialising LiteRT")
        TensorFlowLite.init()

        step("creating the delegate")
        val handle = QnnNative.createDelegate(code, directory)
        if (handle == 0L) {
            return failure(model, backend, "the delegate would not start", QnnNative.error())
        }

        var interpreter: Interpreter? = null
        return try {
            // The delegate's account of the split is printed while the graph is
            // being applied, so the log is read from just before that point.
            val logFrom = DelegateLog.mark(log)
            step("building the interpreter")
            interpreter = Interpreter(
                model,
                Interpreter.Options().addDelegate(Handle(handle)),
            )
            // Sizes are only final after allocation, and a delegate can change
            // them — sizing buffers before this is how the NNAPI path first
            // went wrong.
            step("allocating tensors")
            interpreter.allocateTensors()

            val inputSpecs = specsOf(interpreter, input = true)
            TensorIo.mismatch(inputSpecs, inputs)?.let { throw TensorIo.Mismatch(it) }
            val buffers = inputSpecs.map { spec ->
                ByteBuffer.allocateDirect(spec.bytes).order(ByteOrder.nativeOrder()).apply {
                    if (inputs.isEmpty()) {
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

            fun invoke() {
                buffers.forEach { (it as ByteBuffer).rewind() }
                outputs.values.forEach { it.rewind() }
                interpreter.runForMultipleInputsOutputs(buffers, outputs)
            }

            if (runs > 0) {
                step("warmup")
                repeat(warmup) { invoke() }
            }
            step("timing $runs runs")
            val before = conditions?.invoke()
            val measured = LongArray(runs)
            repeat(runs) { run ->
                val started = System.nanoTime()
                invoke()
                measured[run] = System.nanoTime() - started
            }
            val after = conditions?.invoke()
            // Sorted separately: run order is the whole value of the raw
            // timings, since a throttle is visible as drift across the loop and
            // in nothing else.
            val timings = measured.copyOf().apply { sort() }

            step("reading what the delegate reported")
            val delegation = Delegation.parse(DelegateLog.since(log, logFrom))
            step("reading profiling")
            val profiling = QnnNative.profiling(handle)
            refuseUnattributable(delegation, profiling, QNN_DELEGATE)?.let { reason ->
                // It ran; nothing could be attributed to the Hexagon. A sweep
                // records that and carries on, which is a different thing from
                // the phone being gone.
                return failure(
                    model,
                    backend,
                    reason,
                    DelegateLog.since(log, logFrom).takeLast(600),
                    ResultContract.Code.REFUSED,
                )
            }

            JSONObject()
                .put("ok", true)
                .put("model", model.name)
                .put("sizeBytes", model.length())
                .put("requestedDevice", "qnn-$backend")
                // The contract's headline field, and it was missing from the
                // one path that reaches an NPU. A consumer branching on
                // `executed` saw nothing here and had to guess; the operator
                // matrix guessed "the Hexagon took none of it" for 62 models
                // it had taken every one of. Attribution is already settled by
                // then — refuseUnattributable above returns before this for
                // anything that cannot be claimed.
                .put("executed", if (delegation?.partial == true) "partial" else "accelerator")
                .put("executedBy", "$QNN_DELEGATE:qnn-$backend")
                .put("backend", backend)
                .put("iterations", runs)
                .apply {
                    if (runs > 0) {
                        put("avgUs", timings.average() / 1000.0)
                        put("medianUs", timings[runs / 2] / 1000.0)
                        put("minUs", timings.first() / 1000.0)
                        put("maxUs", timings.last() / 1000.0)
                        put("p90Us", Timings.percentile(timings, 90) / 1000.0)
                        put("p99Us", Timings.percentile(timings, 99) / 1000.0)
                        if (keepTimings) {
                            put("timingsUs", JSONArray(measured.map { it / 1000.0 }))
                        }
                    }
                    DeviceConditions.describe(before, after)?.let { put("conditions", it) }
                }
                .put("profilingBytes", profiling)
                .apply {
                    delegation?.let {
                        put(
                            "delegation",
                            JSONObject()
                                .put("delegated", it.delegated)
                                .put("total", it.total)
                                .put("partitions", it.partitions)
                                .put("delegate", QNN_DELEGATE)
                                .put("describe", it.describe("the Hexagon"))
                                .put("partial", it.partial),
                        )
                    }
                }
                .put("inputs", JSONArray(inputSpecs.map(TensorIo::describe)))
                .put(
                    "outputs",
                    JSONArray(specsOf(interpreter, input = false).map(TensorIo::describe)),
                )
                .apply {
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
        } catch (failed: Throwable) {
            failure(
                model,
                backend,
                "${failed::class.java.simpleName}: ${failed.message.orEmpty()}",
                // The vendor's own words, which are empty when the failure was
                // upstream of the delegate — as it is for a model that would
                // not load. That is the honest value, not a missing one.
                QnnNative.error(),
                when {
                    // Their file and their byte count, so their code.
                    failed is TensorIo.Mismatch -> ResultContract.Code.INVALID_REQUEST
                    // Asked only on a failure: does it load with nothing attached?
                    interpreter == null && modelIsUnloadable(model) ->
                        ResultContract.Code.INVALID_MODEL
                    else -> ResultContract.Code.FAILED
                },
            )
        } finally {
            step("done")
            runCatching { interpreter?.close() }
            QnnNative.destroy(handle)
        }
    }

    private fun failure(
        model: File,
        backend: String,
        reason: String,
        detail: String,
        code: String = ResultContract.Code.FAILED,
    ) =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("model", model.name)
            .put("requestedDevice", "qnn-$backend")
            .put("error", reason)
            .put("detail", detail)
            .toString()

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


    /**
     * A `TfLiteDelegate*` in the shape TFLite expects.
     *
     * Closing is the loader's job, not TFLite's: the same handle is asked for
     * its profiling result after the interpreter has gone.
     */
    private class Handle(private val handle: Long) : Delegate {
        override fun getNativeHandle(): Long = handle
        override fun close() = Unit
    }
}
