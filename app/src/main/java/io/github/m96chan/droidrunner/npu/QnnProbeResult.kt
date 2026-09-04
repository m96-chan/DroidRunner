package io.github.m96chan.droidrunner.npu

import org.json.JSONObject

/**
 * What the isolated process said about Qualcomm's runtime (issue #82, stage 4).
 *
 * Parsed on this side of the boundary so the process holding vendor code stays
 * as small as it can be, and so the judgement that matters — whether this
 * device can actually reach its NPU — is made somewhere it can be tested.
 *
 * Deliberately pessimistic. Anything unparseable, absent or unfamiliar reads
 * as "no": a wrong "yes" here becomes a runner label claiming acceleration
 * that does not happen, which is exactly the problem #80 raised about labels
 * inferred from a SoC name.
 */
internal data class QnnProbeResult(
    val ok: Boolean,
    /** The process that answered, which must not be this one. */
    val pid: Int?,
    val loaded: List<String>,
    /** Library name to the reason it would not open. */
    val failed: Map<String, String>,
    /** Capability name to Qualcomm's answer: 1 supported, 0 not. */
    val capabilities: Map<String, Int>,
    val error: String?,
) {

    /**
     * Whether the Hexagon Tensor Processor is usable.
     *
     * The HTP is what a modern Snapdragon accelerates with, and it is a
     * different thing from [DSP] — that names the older Hexagon DSP backend,
     * reached through `libQnnDsp.so`, which this app does not fetch and which
     * an 8 Gen 3 reports as unsupported. Reading the DSP answer as "can this
     * device accelerate" says no on hardware that plainly can.
     */
    val htpUsable: Boolean
        get() = ok && (capabilities[HTP_QUANT] == SUPPORTED || capabilities[HTP_FP16] == SUPPORTED)

    /** The backends the delegate said yes to, in the order they were asked. */
    val available: List<String>
        get() = capabilities.filterValues { it == SUPPORTED }.keys.sorted()

    /** One line for the panel and the log, saying what was actually learned. */
    fun summary(): String = when {
        error != null -> "QNN unavailable: $error"
        failed.isNotEmpty() ->
            "QNN failed to load ${failed.keys.joinToString()}: ${failed.values.first()}"
        htpUsable -> "QNN loaded; Hexagon HTP available (${available.joinToString()})"
        // Naming the answers rather than just the verdict: a device that says
        // no to everything and one whose delegate never answered look the same
        // from the outside, and the fix is not the same.
        ok -> "QNN loaded, but no HTP backend " +
            "(${capabilities.entries.joinToString { "${it.key}=${it.value}" }})"
        else -> "QNN did not load"
    }

    companion object {
        const val HTP_QUANT = "htpQuant"
        const val HTP_FP16 = "htpFp16"
        const val DSP = "dsp"
        const val SUPPORTED = 1

        fun parse(json: String): QnnProbeResult {
            val root = runCatching { JSONObject(json) }.getOrNull()
                ?: return unavailable("the loader returned no readable answer")

            val libraries = root.optJSONArray("libraries")
            val loaded = mutableListOf<String>()
            val failed = mutableMapOf<String, String>()
            for (index in 0 until (libraries?.length() ?: 0)) {
                val entry = libraries!!.optJSONObject(index) ?: continue
                val name = entry.optString("name").ifBlank { continue }
                if (entry.optBoolean("loaded")) {
                    loaded += name
                } else {
                    failed[name] = entry.optString("error").ifBlank { "would not open" }
                }
            }

            val capabilities = mutableMapOf<String, Int>()
            root.optJSONObject("capabilities")?.let { reported ->
                reported.keys().forEach { key -> capabilities[key] = reported.optInt(key, 0) }
            }

            return QnnProbeResult(
                ok = root.optBoolean("ok"),
                pid = root.optInt("pid").takeIf { it > 0 },
                loaded = loaded,
                failed = failed,
                capabilities = capabilities,
                error = root.optString("error").ifBlank { null },
            )
        }

        fun unavailable(reason: String) = QnnProbeResult(
            ok = false,
            pid = null,
            loaded = emptyList(),
            failed = emptyMap(),
            capabilities = emptyMap(),
            error = reason,
        )
    }
}
