package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.runner.SupervisorStep.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class SupervisorStepTest {
    private fun decide(
        decision: Admission,
        hasProcess: Boolean = false,
        jobRunning: Boolean = false,
        nowMillis: Long = 1_000,
        nextStartAtMillis: Long = 0,
        pausedFor: String? = null,
    ) = SupervisorStep.decide(
        decision,
        hasProcess,
        jobRunning,
        nowMillis,
        nextStartAtMillis,
        pausedFor,
    )

    @Test fun aNormalHoldWaitsForTheRunningJob() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            hasProcess = true,
            jobRunning = true,
        )

        assertEquals(emptyList<Action>(), result.actions)
        assertEquals(null, result.pausedFor)
    }

    @Test fun aHoldStopsTheIdleListenerThenAnnouncesIt() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            hasProcess = true,
        )

        // This is the regression boundary for #35: a hold decision must result
        // in an actual stop action, not merely a paused dashboard.
        assertEquals(
            listOf(Action.Stop("not charging", stopsActiveJob = false), Action.AnnounceHold("not charging")),
            result.actions,
        )
        assertEquals("not charging", result.pausedFor)
    }

    @Test fun criticalHeatInterruptsTheRunningJob() {
        val result = decide(
            Admission.Blocked("critical heat", urgent = true),
            hasProcess = true,
            jobRunning = true,
        )

        assertEquals(
            listOf(Action.Stop("critical heat", stopsActiveJob = true), Action.AnnounceHold("critical heat")),
            result.actions,
        )
    }

    @Test fun theSameHoldIsAnnouncedOnlyOnce() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            pausedFor = "not charging",
        )

        assertEquals(emptyList<Action>(), result.actions)
        assertEquals("not charging", result.pausedFor)
    }

    @Test fun backoffPreventsAnEarlyRestart() {
        val result = decide(
            Admission.Allowed,
            nowMillis = 999,
            nextStartAtMillis = 1_000,
        )

        assertEquals(emptyList<Action>(), result.actions)
    }

    @Test fun recoveryResumesBeforeSweepingAndStarting() {
        val result = decide(
            Admission.Allowed,
            nowMillis = 1_000,
            nextStartAtMillis = 1_000,
            pausedFor = "not charging",
        )

        assertEquals(listOf(Action.Resume, Action.SweepStrays, Action.Start), result.actions)
        assertEquals(null, result.pausedFor)
    }

    @Test fun anExistingListenerIsNeverStartedTwice() {
        val result = decide(Admission.Allowed, hasProcess = true)

        assertEquals(emptyList<Action>(), result.actions)
    }
}
