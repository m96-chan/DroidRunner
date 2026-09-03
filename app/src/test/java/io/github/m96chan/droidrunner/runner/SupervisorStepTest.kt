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
        reportedFor: String? = null,
        held: Boolean = false,
    ) = SupervisorStep.decide(
        decision,
        hasProcess,
        jobRunning,
        nowMillis,
        nextStartAtMillis,
        reportedFor,
        held,
    )

    @Test fun aNormalHoldWaitsForTheRunningJob() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            hasProcess = true,
            jobRunning = true,
        )

        assertEquals(listOf(Action.ReportCondition("not charging")), result.actions)
        assertEquals("not charging", result.reportedFor)
    }

    @Test fun aHoldStopsTheIdleListenerThenAnnouncesIt() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            hasProcess = true,
        )

        // This is the regression boundary for #35: a hold decision must result
        // in an actual stop action, not merely a paused dashboard.
        assertEquals(
            listOf(
                Action.ReportCondition("not charging"),
                Action.Stop("not charging", stopsActiveJob = false),
                Action.AnnounceHold("not charging"),
            ),
            result.actions,
        )
        assertEquals("not charging", result.reportedFor)
        assertEquals(true, result.held)
    }

    @Test fun criticalHeatInterruptsTheRunningJob() {
        val result = decide(
            Admission.Blocked("critical heat", urgent = true),
            hasProcess = true,
            jobRunning = true,
        )

        assertEquals(
            listOf(
                Action.ReportCondition("critical heat"),
                Action.Stop("critical heat", stopsActiveJob = true),
                Action.AnnounceHold("critical heat"),
            ),
            result.actions,
        )
    }

    @Test fun theSameHoldIsAnnouncedOnlyOnce() {
        val result = decide(
            Admission.Blocked("not charging", urgent = false),
            reportedFor = "not charging",
            held = true,
        )

        assertEquals(emptyList<Action>(), result.actions)
        assertEquals("not charging", result.reportedFor)
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
            reportedFor = "not charging",
            held = true,
        )

        assertEquals(listOf(Action.Resume, Action.SweepStrays, Action.Start), result.actions)
        assertEquals(null, result.reportedFor)
    }

    @Test fun aPendingConditionIsReportedWithoutStoppingTheListener() {
        val result = decide(Admission.Pending("not charging"), hasProcess = true)

        assertEquals(listOf(Action.ReportCondition("not charging")), result.actions)
        assertEquals("not charging", result.reportedFor)
        assertEquals(false, result.held)
    }

    @Test fun recoveryFromPendingClearsTheWarningWithoutRestarting() {
        val result = decide(
            Admission.Allowed,
            hasProcess = true,
            reportedFor = "not charging",
        )

        assertEquals(listOf(Action.ClearCondition), result.actions)
    }

    @Test fun anExistingListenerIsNeverStartedTwice() {
        val result = decide(Admission.Allowed, hasProcess = true)

        assertEquals(emptyList<Action>(), result.actions)
    }
}
