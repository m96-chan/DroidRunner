package dev.devenus.droidrunner.npu

import org.json.JSONObject

/**
 * Turns NNAPI probe output into runner labels. SoC-name guesses stay as
 * fallback hints; anything derived here is backed by a driver the device
 * actually reports, which is what `docs/ARCHITECTURE.md` requires before a
 * backend is advertised as usable.
 */
object NpuLabels {

    /**
     * Labels proven by enumeration: `nnapi` when the runtime is usable at all,
     * `nnapi-accelerator` when a non-CPU driver exists, and a vendor label per
     * detected driver family.
     */
    fun fromDevicesJson(devicesJson: String): Set<String> = buildSet {
        val parsed = runCatching { JSONObject(devicesJson) }.getOrNull() ?: return@buildSet
        if (!parsed.optBoolean("available")) return@buildSet
        add("nnapi")
        val devices = parsed.optJSONArray("devices") ?: return@buildSet
        var sawAccelerator = false
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            val name = device.optString("name").lowercase()
            val type = device.optString("type")
            if (type == "accelerator" || type == "gpu") sawAccelerator = true
            when {
                name.startsWith("mtk-") || name.contains("neuron") -> add("npu-neuron")
                name.contains("qti-") || name.contains("hta") || name.contains("dsp-hexagon") ->
                    add("npu-qnn")
                name.contains("google-edgetpu") || name.contains("darwinn") -> add("npu-tflite")
                name.contains("exynos") || name.contains("enn") -> add("npu-enn")
            }
        }
        if (sawAccelerator) add("nnapi-accelerator")
    }

    /** Reads the cached probe result written by [refresh]. */
    fun cached(context: android.content.Context): Set<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()

    /** Runs the probe and caches the labels it proves; returns them. */
    fun refresh(context: android.content.Context): Set<String> {
        val labels = fromDevicesJson(NnapiProbe.devices())
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, labels).apply()
        return labels
    }

    private const val PREFS = "npu_labels"
    private const val KEY = "verified"
}
