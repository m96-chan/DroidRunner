package dev.devenus.droidrunner.runner

import android.content.Context

/** Android `PowerManager` thermal status values, named for readability. */
object ThermalStatus {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4

    fun label(status: Int?): String = when (status) {
        null -> "unknown"
        NONE -> "none"
        LIGHT -> "light"
        MODERATE -> "moderate"
        SEVERE -> "severe"
        CRITICAL -> "critical"
        5 -> "emergency"
        else -> "shutdown"
    }
}

/** Conditions a device must satisfy before it accepts more CI work. */
data class AdmissionThresholds(
    val requireCharging: Boolean = true,
    val minimumBatteryPercent: Int = 30,
    /** New jobs are refused once thermal status exceeds this. */
    val maximumThermalStatus: Int = ThermalStatus.MODERATE,
    val minimumFreeStorageMb: Int = 2048,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("require_charging", requireCharging)
            .putInt("min_battery", minimumBatteryPercent)
            .putInt("max_thermal", maximumThermalStatus)
            .putInt("min_free_mb", minimumFreeStorageMb)
            .apply()
    }

    companion object {
        private const val PREFS = "admission"

        fun load(context: Context): AdmissionThresholds {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = AdmissionThresholds()
            return AdmissionThresholds(
                requireCharging = prefs.getBoolean("require_charging", defaults.requireCharging),
                minimumBatteryPercent = prefs.getInt("min_battery", defaults.minimumBatteryPercent),
                maximumThermalStatus = prefs.getInt("max_thermal", defaults.maximumThermalStatus),
                minimumFreeStorageMb = prefs.getInt("min_free_mb", defaults.minimumFreeStorageMb),
            )
        }
    }
}

/** Device conditions sampled at one point in time. */
data class DeviceConditions(
    val charging: Boolean,
    val batteryPercent: Int,
    /** null when the platform does not report thermal status (API < 29). */
    val thermalStatus: Int?,
    val freeStorageMb: Long,
)

sealed interface Admission {
    /** The device may accept new jobs. */
    data object Allowed : Admission

    /**
     * The device must not accept new jobs. [urgent] additionally means an
     * in-flight job should be abandoned: the phone is too hot to keep going.
     */
    data class Blocked(val reason: String, val urgent: Boolean = false) : Admission
}

/**
 * Decides whether a device should take on more CI work (issue #2). Pure logic
 * so the thresholds are testable without a device.
 *
 * Jobs are held *between* runs rather than killed mid-flight — a build that
 * dies halfway wastes the work already done — except when heat reaches the
 * critical range, where continuing risks the hardware.
 */
object AdmissionPolicy {

    fun evaluate(conditions: DeviceConditions, thresholds: AdmissionThresholds): Admission {
        val thermal = conditions.thermalStatus
        if (thermal != null && thermal >= ThermalStatus.CRITICAL) {
            return Admission.Blocked("thermal ${ThermalStatus.label(thermal)}", urgent = true)
        }
        if (thermal != null && thermal > thresholds.maximumThermalStatus) {
            return Admission.Blocked("cooling down (thermal ${ThermalStatus.label(thermal)})")
        }
        if (thresholds.requireCharging && !conditions.charging) {
            return Admission.Blocked("not charging")
        }
        if (conditions.batteryPercent < thresholds.minimumBatteryPercent) {
            return Admission.Blocked(
                "battery ${conditions.batteryPercent}% below ${thresholds.minimumBatteryPercent}%",
            )
        }
        if (conditions.freeStorageMb < thresholds.minimumFreeStorageMb) {
            return Admission.Blocked(
                "free storage ${conditions.freeStorageMb}MB below ${thresholds.minimumFreeStorageMb}MB",
            )
        }
        return Admission.Allowed
    }
}
