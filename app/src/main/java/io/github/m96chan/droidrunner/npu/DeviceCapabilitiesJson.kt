package io.github.m96chan.droidrunner.npu

import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.device.HexagonVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `/v1/capabilities` payload. Kept out of [DeviceAgentServer] so the
 * server itself has no Android dependencies and can be unit-tested on the JVM.
 */
object DeviceCapabilitiesJson {
    /** HTP generation and the runtime it implies, or why neither is known. */
    private fun hexagon(soc: String): JSONObject {
        val version = HexagonVersion.of(soc)
        return JSONObject()
            .put("version", version ?: JSONObject.NULL)
            .put(
                "libraries",
                version?.let { JSONArray(HexagonVersion.libraries(it)) } ?: JSONObject.NULL,
            )
            .put("unsupported", HexagonVersion.unsupportedReason(soc) ?: JSONObject.NULL)
    }

    /**
     * What TFLite's bundled compatibility list says about this phone.
     *
     * Reported as `allowlisted`, not as `supported`, because that is what it
     * is: a table shipped inside the library, matched against device strings.
     * An SM8650 answers **false** here, which cannot mean the Adreno will not
     * run a graph — it means the phone is newer than the table in 2.16.1.
     *
     * So this is advisory and nothing gates on it. Whether the delegate runs
     * is decided by asking the delegate, which is the same rule the rest of
     * this project follows: a driver's opinion beats a datasheet, and a
     * datasheet is what an allowlist is.
     */
    private fun gpu(): JSONObject = runCatching {
        org.tensorflow.lite.gpu.CompatibilityList().use {
            JSONObject().put("allowlisted", it.isDelegateSupportedOnThisDevice)
        }
    }.getOrElse { JSONObject().put("allowlisted", false).put("error", it.message.orEmpty()) }

    fun build(context: Context): String {
        val capabilities = DeviceCapabilities.detect()
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else -1
        return JSONObject()
            .put("agent", "droidrunner/0.1")
            // Which build answered, as distinct from which version of this API
            // it speaks. A fleet does not update all at once: one phone in ours
            // reported `nnapi-reference` as an accelerator for a whole operator
            // matrix because its build predated the rule that a CPU driver is
            // the CPU, and nothing in this payload said which build it was. It
            // had to be inferred from the shape of its answers.
            .put("appVersion", io.github.m96chan.droidrunner.BuildConfig.VERSION_NAME)
            // The version is not enough on a test fleet, where every phone
            // reports 0.0.0-dev. Absent rather than empty when the build had no
            // git to ask — the corresponding-source archive has none.
            .apply {
                io.github.m96chan.droidrunner.BuildConfig.GIT_COMMIT
                    .takeIf { it.isNotBlank() }
                    ?.let { put("appBuild", it) }
            }
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", capabilities.manufacturer)
                    .put("model", capabilities.model)
                    // The tables a compiler keeps are keyed by SoC, not by
                    // handset model: two phones with the same silicon have the
                    // same drivers and one name says so (#96).
                    .put("soc", capabilities.soc)
                    .put("labels", JSONArray(capabilities.labels().sorted())),
            )
            .put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT))
            .put("thermalStatus", thermal)
            .put("nnapi", JSONObject(NnapiProbe.devices()))
            // The one accelerator every phone has, and the only one this
            // project never asked about until #140. Reported separately from
            // NNAPI because it is not an NNAPI driver: it is TFLite's own
            // delegate, reached by the device name `gpu`.
            .put("gpu", gpu())
            // What this device would need to reach its Hexagon NPU (#82).
            // Reported before anything can use it, so a job can see which
            // devices a future QNN adapter would cover — and so an
            // unrecognised Snapdragon is visible as a gap rather than silence.
            .put("hexagon", hexagon(capabilities.soc))
            .toString()
    }
}
