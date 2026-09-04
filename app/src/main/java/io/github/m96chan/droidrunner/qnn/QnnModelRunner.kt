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

import io.github.m96chan.droidrunner.npu.QnnDelegation
import io.github.m96chan.droidrunner.npu.refuseUnattributable
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

    fun run(
        model: File,
        directory: String,
        libraries: List<String>,
        backend: String,
        iterations: Int,
        warmup: Int = 2,
    ): String {
        val runs = iterations.coerceIn(1, 500)
        val loading = QnnNative.load(directory, libraries)
        if (!JSONObject(loading).optBoolean("ok")) {
            return failure(model, backend, "QNN did not load", loading)
        }

        val handle = QnnNative.createDelegate(
            mapOf(
                "backend_type" to backend,
                // Its own account of what it took is the only trustworthy
                // source for that, and it only says so when logging is on.
                "log_level" to "info",
                // Nothing is recorded unless a graph really ran on the backend,
                // which is a second opinion independent of the log.
                "profiling" to "basic",
            ),
        )
        if (handle == 0L) {
            return failure(model, backend, "the delegate would not start", QnnNative.error())
        }

        var interpreter: Interpreter? = null
        return try {
            // The delegate's account of the split is printed while the graph is
            // being applied, so the log is read from just before that point.
            val logFrom = DelegateLog.mark()
            interpreter = Interpreter(
                model,
                Interpreter.Options().addDelegate(Handle(handle)),
            )
            // Sizes are only final after allocation, and a delegate can change
            // them — sizing buffers before this is how the NNAPI path first
            // went wrong.
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

            repeat(warmup) { invoke() }
            val timings = LongArray(runs)
            repeat(runs) { run ->
                val started = System.nanoTime()
                invoke()
                timings[run] = System.nanoTime() - started
            }
            timings.sort()

            val delegation = QnnDelegation.parse(DelegateLog.since(logFrom))
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
