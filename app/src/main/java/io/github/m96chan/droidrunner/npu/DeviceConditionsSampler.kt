package io.github.m96chan.droidrunner.npu

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Reads [DeviceConditions] off the device (issue #98).
 *
 * Separate from the data it produces so that everything deciding what a
 * measurement *means* stays free of Android types and testable on the JVM,
 * which is the same split [DeviceCapabilitiesJson] makes.
 *
 * Every reading is best-effort. Sampling must never be the thing that fails a
 * measurement: a missing field says less than a wrong one, and an exception
 * here would throw away a run that had already happened.
 */
internal object DeviceConditionsSampler {

    fun sample(context: Context): DeviceConditions = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        DeviceConditions(
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                power?.currentThermalStatus ?: DeviceConditions.UNKNOWN
            } else {
                DeviceConditions.UNKNOWN
            },
            thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) {
                // Documented to return NaN when it has not had long enough
                // since boot, or when it is asked again too soon.
                power?.getThermalHeadroom(FORECAST_SECONDS)?.takeIf { !it.isNaN() }
            } else {
                null
            },
            batteryTemperatureC = battery(context)
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                // Tenths of a degree, which is not a unit anyone wants in a
                // result they are comparing week to week.
                ?.let { it / 10.0 },
            charging = battery(context)
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                ?.let { it != 0 },
            screenOn = power?.isInteractive,
        )
    }.getOrElse { DeviceConditions() }

    /** A sticky broadcast, so this reads the last value rather than waiting. */
    private fun battery(context: Context): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /** How far ahead to ask for headroom. Ten seconds is the documented floor. */
    private const val FORECAST_SECONDS = 10
}
