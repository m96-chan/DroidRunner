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

    @Test fun aRunnerOnItsWayDownSaysSoWhileItIsStillGoingDown() {
        // The stop takes about twenty seconds and no longer holds the UI
        // thread for them (#209). The notification is the only thing on screen
        // when the app is not, and a stop that says nothing is one that gets
        // tapped again — which is how a listener ends up orphaned.
        val stopping = RunnerSnapshot(state = RunnerState.LISTENING, stopping = true)

        assertEquals("Stopping", text(stopping))
    }

    @Test fun stoppingOutranksEvenAJobAndACondition() {
        val stopping = RunnerSnapshot(
            state = RunnerState.JOB_RUNNING,
            currentJob = "build (arm64)",
            pausedReason = "cooling down (thermal severe)",
            stopping = true,
        )

        assertEquals("Stopping", text(stopping))
    }

    @Test fun aRunnerThatFellOverDoesNotReadLikeTheStopButton() {
        // Issue #211: the supervisor used to end on the first exception from
        // anything it touched and land in the same "Stopped" a deliberate stop
        // produces, so the one state nobody should have to guess at was the one
        // that could mean either.
        val failed = RunnerSnapshot(state = RunnerState.STOPPED, failureReason = "StatFs failed")

        assertEquals("Stopped after an error: StatFs failed", text(failed))
        assertEquals("Stopped", text(RunnerSnapshot(state = RunnerState.STOPPED)))
    }

    @Test fun theWordingOnlyChangesWhenSomethingUserVisibleChanged() {
        // The service redraws the notification on distinct text, so a snapshot
        // that only gained a log line must map to the same string.
        val base = RunnerSnapshot(state = RunnerState.LISTENING)
        val noisier = base.copy(recentLog = listOf("some listener chatter"), restarts = 3)
        assertEquals(text(base), text(noisier))
    }
}
