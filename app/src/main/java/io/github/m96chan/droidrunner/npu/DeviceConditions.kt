package io.github.m96chan.droidrunner.npu

import org.json.JSONObject

/**
 * What the phone was doing while a number was measured (issue #98).
 *
 * A phone is not a stable benchmark host. A big-core boost, a warm SoC or a
 * screen that woke up moves a latency further than the compiler change being
 * measured, and admission control (#2) only keeps an already-hot device from
 * picking up work — a job that starts cool can finish throttled, and until now
 * nothing in the result said so.
 *
 * The consumer is a regression gate: fail the job when a kernel gets 8% slower
 * on the MDLA. A gate that also fires when the phone was warm is muted inside a
 * week, and then the real regressions arrive unnoticed.
 */
internal data class DeviceConditions(
    /** `PowerManager.getCurrentThermalStatus`, or [UNKNOWN] below API 29. */
    val thermalStatus: Int = UNKNOWN,
    /** `getThermalHeadroom`, API 30+, and NaN often enough to be nullable. */
    val thermalHeadroom: Float? = null,
    val batteryTemperatureC: Double? = null,
    val charging: Boolean? = null,
    /** Screen state: a display that woke mid-run competes for the same power. */
    val screenOn: Boolean? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("thermalStatus", thermalStatus)
        .apply {
            thermalHeadroom?.let { put("thermalHeadroom", it.toDouble()) }
            batteryTemperatureC?.let { put("batteryTemperatureC", it) }
            charging?.let { put("charging", it) }
            screenOn?.let { put("screenOn", it) }
        }

    companion object {
        const val UNKNOWN = -1

        /**
         * Both ends of the timing loop, and whether the run can be trusted.
         *
         * [stable] is true only when both ends reported a thermal status and
         * the two agree. A device that would not say counts as unstable, on
         * the same reasoning as [refuseUnattributable]: silence is not a yes,
         * and a gate should not treat "we could not tell" as "it was fine".
         */
        fun describe(before: DeviceConditions?, after: DeviceConditions?): JSONObject? {
            if (before == null || after == null) return null
            val known = before.thermalStatus >= 0 && after.thermalStatus >= 0
            return JSONObject()
                .put("stable", known && before.thermalStatus == after.thermalStatus)
                .put("before", before.json())
                .put("after", after.json())
        }
    }
}
