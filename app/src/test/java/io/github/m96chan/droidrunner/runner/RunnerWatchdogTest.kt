package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertFalse
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
}
