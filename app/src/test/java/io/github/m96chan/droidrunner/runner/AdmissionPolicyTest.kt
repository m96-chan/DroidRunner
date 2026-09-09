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
        previous: AdmissionPolicy.State = AdmissionPolicy.State(),
    ) = AdmissionPolicy.evaluate(conditions, thresholds, previous)

    private fun admission(
        conditions: DeviceConditions = healthy,
        thresholds: AdmissionThresholds = defaults,
        samples: Int = AdmissionPolicy.SAMPLES_BEFORE_HOLD,
    ): Admission {
        var state = AdmissionPolicy.State()
        var result = AdmissionPolicy.Result(Admission.Allowed, state)
        repeat(samples) {
            result = evaluate(conditions, thresholds, state)
            state = result.state
        }
        return result.admission
    }

    @Test fun healthyDeviceAcceptsJobs() {
        assertEquals(Admission.Allowed, admission())
    }

    @Test fun aPowerCutAloneDoesNotStopAChargedPhone() {
        assertEquals(
            "a charged phone is a UPS; losing mains is not a reason to stop (#185)",
            Admission.Allowed,
            admission(healthy.copy(charging = false)),
        )
    }

    @Test fun aDeviceSetToMainsOnlyStillRefusesBatteryWork() {
        val blocked = admission(
            healthy.copy(charging = false),
            defaults.copy(requireMains = true),
        ) as Admission.Blocked
        assertEquals("running on battery", blocked.reason)
        assertFalse("a running build should survive being unplugged", blocked.urgent)
    }

    @Test fun holdsJobsOnBatteryBelowTheThreshold() {
        val blocked =
            admission(healthy.copy(charging = false, batteryPercent = 12)) as Admission.Blocked
        assertTrue(blocked.reason.contains("12%"))
        assertFalse(blocked.urgent)
    }

    @Test fun aLowReadingWhilePluggedInIsABatteryOnItsWayUp() {
        assertEquals(Admission.Allowed, admission(healthy.copy(batteryPercent = 12)))
    }

    @Test fun batteryExactlyAtThresholdIsAccepted() {
        assertEquals(
            Admission.Allowed,
            admission(healthy.copy(charging = false, batteryPercent = 30)),
        )
    }

    @Test fun holdsJobsAboveTheThermalThreshold() {
        val blocked = admission(healthy.copy(thermalStatus = ThermalStatus.SEVERE)) as Admission.Blocked
        assertTrue(blocked.reason.contains("cooling down"))
        assertFalse("severe heat waits for the job to finish", blocked.urgent)
    }

    @Test fun thermalAtThresholdIsStillAccepted() {
        assertEquals(Admission.Allowed, admission(healthy.copy(thermalStatus = ThermalStatus.MODERATE)))
    }

    @Test fun criticalHeatInterruptsARunningJob() {
        val blocked = admission(healthy.copy(thermalStatus = ThermalStatus.CRITICAL), samples = 1) as Admission.Blocked
        assertTrue("critical heat must abandon the job", blocked.urgent)
    }

    @Test fun criticalHeatOverridesARaisedThermalThreshold() {
        val permissive = defaults.copy(maximumThermalStatus = 6)
        val blocked = admission(healthy.copy(thermalStatus = 5), permissive, samples = 1) as Admission.Blocked
        assertTrue(blocked.urgent)
    }

    @Test fun unknownThermalStatusDoesNotBlock() {
        assertEquals(Admission.Allowed, admission(healthy.copy(thermalStatus = null)))
    }

    @Test fun holdsJobsWhenStorageIsLow() {
        val blocked = admission(healthy.copy(freeStorageMb = 100)) as Admission.Blocked
        assertTrue(blocked.reason.contains("free storage 100MB"))
    }

    @Test fun heatIsReportedBeforeOtherProblems() {
        // A hot, unplugged, nearly-empty phone should name the heat: it is the
        // condition that decides whether the running job survives.
        val blocked = admission(
            DeviceConditions(
                charging = false,
                batteryPercent = 5,
                thermalStatus = ThermalStatus.CRITICAL,
                freeStorageMb = 10,
            ),
            samples = 1,
        ) as Admission.Blocked
        assertTrue(blocked.reason.contains("thermal"))
        assertTrue(blocked.urgent)
    }

    @Test fun momentaryConditionIsReportedButDoesNotHold() {
        val first = evaluate(healthy.copy(charging = false, batteryPercent = 12))
        assertEquals(Admission.Pending(FLAT), first.admission)

        val recovered = evaluate(healthy, previous = first.state)
        assertEquals(Admission.Allowed, recovered.admission)
        assertEquals(AdmissionPolicy.State(), recovered.state)
    }

    @Test fun sustainedConditionHoldsOnThirdSample() {
        var state = AdmissionPolicy.State()
        repeat(AdmissionPolicy.SAMPLES_BEFORE_HOLD - 1) {
            val result =
                evaluate(healthy.copy(charging = false, batteryPercent = 12), previous = state)
            assertEquals(Admission.Pending(FLAT), result.admission)
            state = result.state
        }
        assertEquals(
            Admission.Blocked(FLAT),
            evaluate(healthy.copy(charging = false, batteryPercent = 12), previous = state)
                .admission,
        )
    }

    @Test fun aDifferentProblemRestartsConfirmation() {
        val flat = evaluate(healthy.copy(charging = false, batteryPercent = 12))
        val lowDisk = evaluate(healthy.copy(freeStorageMb = 100), previous = flat.state)
        assertEquals(
            Admission.Pending("free storage 100MB below 2048MB"),
            lowDisk.admission,
        )
        assertEquals(1, lowDisk.state.consecutiveSamples)
    }

    private companion object {
        /** What the policy calls a phone on battery and under the floor. */
        const val FLAT = "on battery, 12% below 30%"
    }
}
