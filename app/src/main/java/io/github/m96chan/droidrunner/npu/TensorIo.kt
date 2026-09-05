/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 *
 * The notice is here because both runners use this and one of them is loaded
 * into the process that holds Qualcomm's libraries. Nothing in this file
 * touches them; it shares their address space, which is the line that matters.
 */
package io.github.m96chan.droidrunner.npu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * Caller-supplied inputs and returned outputs for a model run (issue #92).
 *
 * The existing benchmark fills every input with `(position % 251)` and throws
 * the outputs away, which is right for a latency number and useless for
 * anything else: it can say a graph was accepted, never that it was correct.
 * A compiler cares about the second one — a lowering bug that only appears on
 * the vendor's silicon looks exactly like a working compiler when the check
 * runs on an x86 reference.
 *
 * Kept apart from either runner because both need it and they may not be
 * linked together: one runs beside PRoot, the other in the isolated process
 * with Qualcomm's libraries. What is here is pure — bytes in, bytes out, and
 * the arithmetic that says whether a file is the right size for a tensor —
 * so it is also the part worth testing without a device.
 */
internal object TensorIo {

    /** What the interpreter says a tensor is, once tensors are allocated. */
    data class Spec(
        val index: Int,
        val name: String,
        val type: String,
        val shape: List<Int>,
        val bytes: Int,
        /** Non-null only for a quantized tensor. */
        val scale: Float? = null,
        val zeroPoint: Int? = null,
    ) {
        fun describe(): String = "$name ($type ${shape.joinToString("×")}, $bytes bytes)"
    }

    /**
     * Why [provided] cannot be fed to [expected], or null when it can.
     *
     * Checked after `allocateTensors()`, because that is where shapes stop
     * being guessable — a delegate can change them, and sizing anything before
     * it is how the first version of this went wrong. The message names the
     * mismatch rather than reporting a failure whose cause is somewhere else
     * entirely.
     */
    /**
     * What the caller supplied does not fit what the model declares.
     *
     * Its own type so the failure can be reported as the caller's rather than
     * as something that went wrong inside — the same distinction `invalid-model`
     * draws for the file itself. Reported by the first outside consumer, whose
     * fixtures were 1024 elements for a model declaring 1.
     */
    class Mismatch(message: String) : IllegalArgumentException(message)

    fun mismatch(expected: List<Spec>, provided: List<File>): String? {
        if (provided.isEmpty()) return null
        if (provided.size != expected.size) {
            return "this model takes ${expected.size} input tensor(s) and " +
                "${provided.size} file(s) were given: " +
                expected.joinToString { it.describe() }
        }
        expected.zip(provided).forEach { (spec, file) ->
            if (!file.isFile) return "input ${spec.index} (${spec.name}): ${file.name} is not a file"
            if (file.length() != spec.bytes.toLong()) {
                return "input ${spec.index} ${spec.describe()} needs ${spec.bytes} bytes, " +
                    "but ${file.name} is ${file.length()}"
            }
        }
        return null
    }

    /** Fills [buffer] from [file], which [mismatch] has already sized. */
    fun load(buffer: ByteBuffer, file: File) {
        buffer.rewind()
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                buffer.put(chunk, 0, count)
            }
        }
        buffer.rewind()
    }

    /**
     * Where outputs go, in both frames of reference.
     *
     * [directory] is where this process writes; [asJobSeesIt] is the same place
     * as the caller named it. They differ — the guest's `/home/runner` is a
     * bind mount of a directory under `/data/data` — and reporting the host
     * side would hand a job a path it cannot open and disclose the device's
     * layout for nothing.
     */
    data class Target(val directory: File, val asJobSeesIt: String)

    /** Writes [buffer] to a file named for its tensor, and reports what it wrote. */
    fun save(target: Target, spec: Spec, buffer: ByteBuffer): JSONObject {
        val directory = target.directory
        directory.mkdirs()
        val file = File(directory, fileName(spec))
        buffer.rewind()
        file.outputStream().use { out ->
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            out.write(bytes)
        }
        buffer.rewind()
        return describe(spec)
            .put("path", "${target.asJobSeesIt.trimEnd('/')}/${fileName(spec)}")
            .put("bytes", file.length())
    }

    /**
     * A tensor's name is the graph's, so it can contain anything; only the
     * index is guaranteed unique and safe to build a path from.
     */
    fun fileName(spec: Spec): String = "output-${spec.index}.bin"

    /**
     * The tensor as JSON, carrying the quantization parameters when it has
     * them: a caller holding int8 bytes and no scale would be reduced to
     * inferring one from the numbers.
     */
    fun describe(spec: Spec): JSONObject = JSONObject()
        .put("index", spec.index)
        .put("name", spec.name)
        .put("type", spec.type)
        .put("shape", JSONArray(spec.shape))
        .put("bytes", spec.bytes)
        .apply {
            if (spec.scale != null && spec.scale != 0f) {
                put(
                    "quantizationParams",
                    JSONObject().put("scale", spec.scale).put("zeroPoint", spec.zeroPoint ?: 0),
                )
            }
        }
}
