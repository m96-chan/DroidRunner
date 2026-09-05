package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.runner.RunnerState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a runner that is visibly not running should be told about (the report
 * that produced this: a phone off the charger, and a Re-register button that
 * looked broken).
 */
class BlockedUntilStoppedTest {

    @Test fun aStoppedRunnerBlocksNothing() {
        assertNull(blockedUntilStopped(RunnerState.STOPPED, null, "re-registering"))
    }

    @Test fun aPausedRunnerIsNotToldToStopSomethingItCanSeeIsNotRunning() {
        val message = blockedUntilStopped(
            RunnerState.PAUSED, "not charging", "re-registering",
        )!!

        // It names the hold and its reason, rather than asserting the runner is
        // running when the screen beside it says paused.
        assertTrue(message.contains("Held by admission control"))
        assertTrue(message.contains("not charging"))
    }

    @Test fun aPausedRunnerIsToldWhyStoppingIsStillRequired() {
        // Admission control stopped the listener, but the supervisor restarts
        // it the moment the hold clears — plugging the charger back in during
        // config.sh would meet a half-written identity.
        val message = blockedUntilStopped(RunnerState.PAUSED, "battery low", "re-registering")!!

        assertTrue(message.contains("start again by itself"))
        assertTrue(message.contains("stop it from the dashboard"))
    }

    @Test fun aHoldWithNoStatedReasonStillReads() {
        val message = blockedUntilStopped(RunnerState.PAUSED, null, "installing a runtime")!!

        assertTrue(message.contains("Held by admission control,"))
        assertTrue(message.contains("installing a runtime"))
    }

    @Test fun aRunningListenerIsToldPlainly() {
        for (state in listOf(RunnerState.LISTENING, RunnerState.JOB_RUNNING, RunnerState.STARTING)) {
            val message = blockedUntilStopped(state, null, "re-registering")!!
            assertTrue(message.contains("Stop the runner first"))
        }
    }
}
