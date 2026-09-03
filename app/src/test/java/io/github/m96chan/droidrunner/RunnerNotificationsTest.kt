package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerNotificationsTest {

    private fun text(snapshot: RunnerSnapshot) = RunnerNotifications.statusText(snapshot)

    @Test fun eachStateHasItsOwnWording() {
        assertEquals("Stopped", text(RunnerSnapshot(state = RunnerState.STOPPED)))
        assertEquals("Starting", text(RunnerSnapshot(state = RunnerState.STARTING)))
        assertEquals("Listening for jobs", text(RunnerSnapshot(state = RunnerState.LISTENING)))
    }

    @Test fun aHeldRunnerSaysWhy() {
        // Held and broken look identical from GitHub's side; the reason is the
        // whole reason this line exists.
        val held = RunnerSnapshot(state = RunnerState.PAUSED, pausedReason = "not charging")
        assertTrue(text(held).contains("not charging"))
    }

    @Test fun aHeldRunnerWithoutAReasonStillSaysItIsHolding() {
        val held = RunnerSnapshot(state = RunnerState.PAUSED)
        assertEquals("Holding jobs: device is not ready", text(held))
    }

    @Test fun aPendingConditionSaysTheRunnerIsStillActive() {
        val pending = RunnerSnapshot(state = RunnerState.LISTENING, pausedReason = "not charging")
        assertEquals("Condition: not charging — still running", text(pending))
    }

    @Test fun aRunningJobIsNamedWhenTheListenerReportedOne() {
        val running = RunnerSnapshot(state = RunnerState.JOB_RUNNING, currentJob = "build (arm64)")
        assertEquals("Running build (arm64)", text(running))
        assertEquals("Running a job", text(RunnerSnapshot(state = RunnerState.JOB_RUNNING)))
    }

    @Test fun theWordingOnlyChangesWhenSomethingUserVisibleChanged() {
        // The service redraws the notification on distinct text, so a snapshot
        // that only gained a log line must map to the same string.
        val base = RunnerSnapshot(state = RunnerState.LISTENING)
        val noisier = base.copy(recentLog = listOf("some listener chatter"), restarts = 3)
        assertEquals(text(base), text(noisier))
    }
}
