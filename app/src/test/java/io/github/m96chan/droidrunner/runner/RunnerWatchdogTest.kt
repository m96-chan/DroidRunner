package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerWatchdogTest {

    @Test fun startsAConfiguredDeviceThatIsMeantToRun() {
        assertTrue(RunnerWatchdog.shouldStart(configured = true, runtimeInstalled = true, autostart = true))
    }

    @Test fun leavesADeviceThatWasNeverRegisteredAlone() {
        assertFalse(RunnerWatchdog.shouldStart(configured = false, runtimeInstalled = true, autostart = true))
    }

    @Test fun willNotStartWithoutTheRuntimeItWouldHaveToRun() {
        assertFalse(RunnerWatchdog.shouldStart(configured = true, runtimeInstalled = false, autostart = true))
    }

    @Test fun respectsAnOwnerWhoTurnedAutostartOff() {
        // The watchdog answers "should this phone be running", not "is it
        // running" — so an owner who asked it not to start on its own must not
        // be overruled fifteen minutes later.
        assertFalse(RunnerWatchdog.shouldStart(configured = true, runtimeInstalled = true, autostart = false))
    }

    // --- what a tick is allowed to claim happened (issue #214) --------------

    @Test fun aTickThatFoundTheRunnerUpClaimsNothing() {
        // Re-booked every sixteen minutes, this is about ninety times a day on
        // a phone that never missed a beat. Every one of those lines used to
        // say the runner had not been running.
        assertNull(RunnerWatchdog.startedLine(RunnerState.LISTENING))
        assertNull(RunnerWatchdog.startedLine(RunnerState.JOB_RUNNING))
        assertNull(RunnerWatchdog.startedLine(RunnerState.STARTING))
        // Held by admission control is still a service that is running; the
        // supervisor is up and will start the listener when the hold clears.
        assertNull(RunnerWatchdog.startedLine(RunnerState.PAUSED))
    }

    @Test fun aTickThatActuallyRecoveredADeadRunnerSaysSo() {
        // The line that the watchdog exists to produce, and the one that is
        // worth counting in a log precisely because it is now rare.
        assertEquals(
            "watchdog (job): the runner was not running, started it",
            RunnerWatchdog.startedLine(RunnerState.STOPPED),
        )
    }
}
