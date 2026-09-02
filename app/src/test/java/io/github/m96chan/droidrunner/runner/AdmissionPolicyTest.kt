package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionPolicyTest {
    private val healthy = DeviceConditions(
        charging = true,
        batteryPercent = 90,
        thermalStatus = ThermalStatus.NONE,
        freeStorageMb = 20_000,
    )
    private val defaults = AdmissionThresholds()

    private fun evaluate(
        conditions: DeviceConditions = healthy,
        thresholds: AdmissionThresholds = defaults,
    ) = AdmissionPolicy.evaluate(conditions, thresholds)

    @Test fun healthyDeviceAcceptsJobs() {
        assertEquals(Admission.Allowed, evaluate())
    }

    @Test fun holdsJobsWhenNotCharging() {
        val blocked = evaluate(healthy.copy(charging = false)) as Admission.Blocked
        assertEquals("not charging", blocked.reason)
        assertFalse("a running build should survive being unplugged", blocked.urgent)
    }

    @Test fun chargingRequirementCanBeDisabled() {
        assertEquals(
            Admission.Allowed,
            evaluate(healthy.copy(charging = false), defaults.copy(requireCharging = false)),
        )
    }

    @Test fun holdsJobsBelowTheBatteryThreshold() {
        val blocked = evaluate(healthy.copy(batteryPercent = 12)) as Admission.Blocked
        assertTrue(blocked.reason.contains("battery 12%"))
        assertFalse(blocked.urgent)
    }

    @Test fun batteryExactlyAtThresholdIsAccepted() {
        assertEquals(Admission.Allowed, evaluate(healthy.copy(batteryPercent = 30)))
    }

    @Test fun holdsJobsAboveTheThermalThreshold() {
        val blocked = evaluate(healthy.copy(thermalStatus = ThermalStatus.SEVERE)) as Admission.Blocked
        assertTrue(blocked.reason.contains("cooling down"))
        assertFalse("severe heat waits for the job to finish", blocked.urgent)
    }

    @Test fun thermalAtThresholdIsStillAccepted() {
        assertEquals(Admission.Allowed, evaluate(healthy.copy(thermalStatus = ThermalStatus.MODERATE)))
    }

    @Test fun criticalHeatInterruptsARunningJob() {
        val blocked = evaluate(healthy.copy(thermalStatus = ThermalStatus.CRITICAL)) as Admission.Blocked
        assertTrue("critical heat must abandon the job", blocked.urgent)
    }

    @Test fun criticalHeatOverridesARaisedThermalThreshold() {
        val permissive = defaults.copy(maximumThermalStatus = 6)
        val blocked = evaluate(healthy.copy(thermalStatus = 5), permissive) as Admission.Blocked
        assertTrue(blocked.urgent)
    }

    @Test fun unknownThermalStatusDoesNotBlock() {
        assertEquals(Admission.Allowed, evaluate(healthy.copy(thermalStatus = null)))
    }

    @Test fun holdsJobsWhenStorageIsLow() {
        val blocked = evaluate(healthy.copy(freeStorageMb = 100)) as Admission.Blocked
        assertTrue(blocked.reason.contains("free storage 100MB"))
    }

    @Test fun heatIsReportedBeforeOtherProblems() {
        // A hot, unplugged, nearly-empty phone should name the heat: it is the
        // condition that decides whether the running job survives.
        val blocked = evaluate(
            DeviceConditions(
                charging = false,
                batteryPercent = 5,
                thermalStatus = ThermalStatus.CRITICAL,
                freeStorageMb = 10,
            ),
        ) as Admission.Blocked
        assertTrue(blocked.reason.contains("thermal"))
        assertTrue(blocked.urgent)
    }
}
