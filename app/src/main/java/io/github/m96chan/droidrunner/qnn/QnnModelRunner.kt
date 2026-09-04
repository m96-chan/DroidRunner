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

import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
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

    /**
     * Where this process says how far it got.
     *
     * A vendor library that segfaults takes the process with it, and there is
     * no reply, no stack and — on a phone whose ROM has no working logcat, as
     * the nubia's has not — no log either. A breadcrumb on disk is what is left
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
    ): String {
        breadcrumb = File(directory).parentFile?.let { File(it, "qnn-last-run.txt") }
        File(directory).parentFile?.let { QnnNative.captureOutput(File(it, "qnn-output.txt").path) }
        step("starting $backend on ${model.name}")
        val runs = iterations.coerceIn(1, 500)
        step("loading libraries")
        val loading = QnnNative.load(directory, libraries)
        if (!JSONObject(loading).optBoolean("ok")) {
            return failure(model, backend, "QNN did not load", loading)
        }

        val options = QnnOptions.forRun(backend)
            ?: return failure(model, backend, "'$backend' is not a QNN backend", "")
        step("creating the delegate")
        val handle = QnnNative.createDelegate(options)
        if (handle == 0L) {
            return failure(model, backend, "the delegate would not start", QnnNative.error())
        }

        var interpreter: Interpreter? = null
        return try {
            // The delegate's account of the split is printed while the graph is
            // being applied, so the log is read from just before that point.
            val logFrom = DelegateLog.mark()
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

            val inputs = (0 until interpreter.inputTensorCount).map { index ->
                ByteBuffer
                    .allocateDirect(interpreter.getInputTensor(index).numBytes())
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        while (remaining() > 0) put((position() % 251).toByte())
                        rewind()
                    }
            }.toTypedArray<Any>()
            val outputs = (0 until interpreter.outputTensorCount).associate { index ->
                index to ByteBuffer
                    .allocateDirect(interpreter.getOutputTensor(index).numBytes())
                    .order(ByteOrder.nativeOrder())
            }

            fun invoke() {
                inputs.forEach { (it as ByteBuffer).rewind() }
                outputs.values.forEach { it.rewind() }
                interpreter.runForMultipleInputsOutputs(inputs, outputs)
            }

            step("warmup")
            repeat(warmup) { invoke() }
            step("timing $runs runs")
            val timings = LongArray(runs)
            repeat(runs) { run ->
                val started = System.nanoTime()
                invoke()
                timings[run] = System.nanoTime() - started
            }
            timings.sort()

            step("reading what the delegate reported")
            val delegation = QnnDelegation.parse(DelegateLog.since(logFrom))
            step("reading profiling")
            val profiling = QnnNative.profiling(handle)
            refuseUnattributable(delegation, profiling)?.let { reason ->
                return failure(model, backend, reason, DelegateLog.since(logFrom).takeLast(600))
            }

            JSONObject()
                .put("ok", true)
                .put("model", model.name)
                .put("sizeBytes", model.length())
                .put("requestedDevice", "qnn-$backend")
                .put("backend", backend)
                .put("iterations", runs)
                .put("avgUs", timings.average() / 1000.0)
                .put("medianUs", timings[runs / 2] / 1000.0)
                .put("minUs", timings.first() / 1000.0)
                .put("maxUs", timings.last() / 1000.0)
                .put("profilingBytes", profiling)
                .apply {
                    delegation?.let {
                        put(
                            "delegation",
                            JSONObject()
                                .put("delegated", it.delegated)
                                .put("total", it.total)
                                .put("partitions", it.partitions)
                                .put("describe", it.describe())
                                .put("partial", it.partial),
                        )
                    }
                }
                .put("inputs", shapes(interpreter, input = true))
                .put("outputs", shapes(interpreter, input = false))
                .toString()
        } catch (failed: Throwable) {
            failure(
                model,
                backend,
                "${failed::class.java.simpleName}: ${failed.message.orEmpty()}",
                QnnNative.error(),
            )
        } finally {
            step("done")
            runCatching { interpreter?.close() }
            QnnNative.destroy(handle)
        }
    }

    private fun failure(model: File, backend: String, reason: String, detail: String) =
        JSONObject()
            .put("ok", false)
            .put("model", model.name)
            .put("requestedDevice", "qnn-$backend")
            .put("error", reason)
            .put("detail", detail)
            .toString()

    private fun shapes(interpreter: Interpreter, input: Boolean): JSONArray {
        val count = if (input) interpreter.inputTensorCount else interpreter.outputTensorCount
        return JSONArray().apply {
            for (index in 0 until count) {
                val tensor =
                    if (input) interpreter.getInputTensor(index)
                    else interpreter.getOutputTensor(index)
                put(
                    JSONObject()
                        .put("name", tensor.name())
                        .put("type", tensor.dataType().name)
                        .put("shape", JSONArray(tensor.shape().toList())),
                )
            }
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
