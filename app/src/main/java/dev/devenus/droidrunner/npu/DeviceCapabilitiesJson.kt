package dev.devenus.droidrunner.npu

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.devenus.droidrunner.device.DeviceCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `/v1/capabilities` payload. Kept out of [DeviceAgentServer] so the
 * server itself has no Android dependencies and can be unit-tested on the JVM.
 */
object DeviceCapabilitiesJson {
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
            .toString()
    }
}
