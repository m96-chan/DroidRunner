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
            .put("runtimeLibrary", version?.let(HexagonVersion::runtimeLibrary) ?: JSONObject.NULL)
            .put("unsupported", HexagonVersion.unsupportedReason(soc) ?: JSONObject.NULL)
    }

    fun build(context: Context): String {
        val capabilities = DeviceCapabilities.detect()
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else -1
        return JSONObject()
            .put("agent", "droidrunner/0.1")
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", capabilities.manufacturer)
                    .put("model", capabilities.model)
                    .put("labels", JSONArray(capabilities.labels().sorted())),
            )
            .put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT))
            .put("thermalStatus", thermal)
            .put("nnapi", JSONObject(NnapiProbe.devices()))
            // What this device would need to reach its Hexagon NPU (#82).
            // Reported before anything can use it, so a job can see which
            // devices a future QNN adapter would cover — and so an
            // unrecognised Snapdragon is visible as a gap rather than silence.
            .put("hexagon", hexagon(capabilities.soc))
            .toString()
    }
}
