package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a result says about the conditions it was measured under (issue #98). */
class MeasurementConditionsTest {

    private fun ascending(vararg values: Long) = values.sortedArray()

    @Test fun everyPercentileIsAValueSomeIterationActuallyProduced() {
        // Interpolating a p99 out of 30 samples invents a latency nothing
        // measured, and these are numbers a caller may want to point at.
        val timings = ascending(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

        assertTrue(Timings.percentile(timings, 90) in timings.toList())
        assertTrue(Timings.percentile(timings, 99) in timings.toList())
    }

    @Test fun theTailIsTheTailAndNotTheMiddle() {
        val timings = ascending(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

        assertEquals(90L, Timings.percentile(timings, 90))
        assertEquals(100L, Timings.percentile(timings, 99))
        assertEquals(50L, Timings.percentile(timings, 50))
    }

    @Test fun aSingleIterationIsItsOwnEveryPercentile() {
        val timings = ascending(42)

        assertEquals(42L, Timings.percentile(timings, 0))
        assertEquals(42L, Timings.percentile(timings, 99))
    }

    @Test fun aLoopThatRanNoIterationsAsksForNothingOutOfBounds() {
        // `iterations: 0` is a real request (#94), so this is reachable.
        assertEquals(0L, Timings.percentile(LongArray(0), 90))
    }

    @Test fun aThermalStatusThatHeldMakesTheRunStable() {
        val steady = DeviceConditions(thermalStatus = 0)

        val described = DeviceConditions.describe(steady, steady)!!

        assertTrue(described.getBoolean("stable"))
    }

    @Test fun aRunThatWarmedUpUnderneathSaysSo() {
        val described = DeviceConditions.describe(
            DeviceConditions(thermalStatus = 0),
            DeviceConditions(thermalStatus = 2),
        )!!

        assertFalse(described.getBoolean("stable"))
        assertEquals(2, described.getJSONObject("after").getInt("thermalStatus"))
    }

    @Test fun aDeviceThatWouldNotSayIsNotCountedAsStable() {
        // Silence is not a yes — the same reasoning as refuseUnattributable.
        // A gate must not read "we could not tell" as "it was fine".
        val described = DeviceConditions.describe(
            DeviceConditions(thermalStatus = DeviceConditions.UNKNOWN),
            DeviceConditions(thermalStatus = DeviceConditions.UNKNOWN),
        )!!

        assertFalse(described.getBoolean("stable"))
    }

    @Test fun aRunWithNoSamplerReportsNoConditionsRatherThanEmptyOnes() {
        // An empty `conditions` object would read as "measured, and nothing
        // was wrong", which is a stronger claim than not measuring at all.
        assertNull(DeviceConditions.describe(null, null))
        assertNull(DeviceConditions.describe(DeviceConditions(), null))
    }

    @Test fun aFieldTheDeviceCouldNotReadIsAbsentRatherThanZero() {
        // Zero is a real battery temperature and a real thermal headroom.
        val json = DeviceConditions(thermalStatus = 1).json()

        assertEquals(1, json.getInt("thermalStatus"))
        assertFalse(json.has("batteryTemperatureC"))
        assertFalse(json.has("thermalHeadroom"))
        assertFalse(json.has("charging"))
    }

    @Test fun aBatchRowCanAskForItsOwnRawTimings() {
        val entries = BatchRequest.entries(
            org.json.JSONObject(
                """{"models":[{"id":"a","path":"/home/runner/a.tflite","timings":true},
                              {"id":"b","path":"/home/runner/b.tflite"}]}""",
            ),
        )

        assertTrue(entries[0].keepTimings)
        assertFalse(entries[1].keepTimings)
    }
}
