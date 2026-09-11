package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.runner.SupervisorStep.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- what the end of a listener meant (issue #210) ----------------------

    /** A hold kills the listener with SIGINT, which the runner reports as 130. */
    private fun heldExit() = ListenerExit.classify(
        stopRequested = false,
        stoppedOnPurpose = true,
        ephemeral = false,
        exitCode = 130,
    )

    @Test fun aListenerTheSupervisorStoppedIsNotACrash() {
        assertEquals(ListenerExit.Kind.POLICY_STOP, heldExit())
        assertFalse(ListenerExit.countsAsFailure(heldExit()))
    }

    @Test fun aListenerNobodyStoppedIsStillACrash() {
        // The same exit code. Only the supervisor knows the difference, which
        // is the whole reason it has to say.
        val exit = ListenerExit.classify(
            stopRequested = false,
            stoppedOnPurpose = false,
            ephemeral = false,
            exitCode = 130,
        )

        assertEquals(ListenerExit.Kind.FAILURE, exit)
        assertTrue(ListenerExit.countsAsFailure(exit))
    }

    @Test fun aHoldFollowedByAClearRestartsWithoutDelay() {
        // The hold arrives about fifteen seconds after each start — three
        // samples at the five-second poll — so the run is never long enough to
        // count as healthy and the backoff only ever grew. Since `Start` is
        // gated on nextStartAtMillis, that backoff was served *after* the
        // charger went back in: up to five minutes of a phone doing nothing
        // with the dashboard reading "retrying in 300s".
        var restartDelayMs = 8 * RestartPolicy.INITIAL_DELAY_MS
        var nextStartAtMillis = 900_000L

        if (ListenerExit.clearsBackoff(heldExit())) {
            restartDelayMs = 0
            nextStartAtMillis = 0
        }

        assertEquals(0L, restartDelayMs)
        val recovered = decide(
            Admission.Allowed,
            hasProcess = false,
            nowMillis = 1_000,
            nextStartAtMillis = nextStartAtMillis,
            reportedFor = "on battery, 12% below 30%",
            held = true,
        )
        assertEquals(listOf(Action.Resume, Action.SweepStrays, Action.Start), recovered.actions)
    }

    @Test fun fourConsecutiveHoldsRaiseNoAlert() {
        // Four was the number that produced "DroidRunner is not staying up" for
        // a runner that was staying up exactly as designed — a phone on a
        // flaky socket, which is the ordinary case this fleet runs in.
        var consecutiveFailures = 0
        var alerted = false

        repeat(4) {
            val exit = heldExit()
            if (ListenerExit.countsAsFailure(exit)) {
                val record = AlertPolicy.recordFailure(
                    AlertPolicy.Failure.LISTENER,
                    consecutiveFailures,
                    alerted,
                )
                consecutiveFailures = record.consecutiveFailures
                alerted = record.alerted
            }
        }

        assertEquals(0, consecutiveFailures)
        assertFalse(alerted)
        assertFalse(AlertPolicy.shouldAlert(AlertPolicy.Failure.LISTENER, consecutiveFailures, alerted))
    }

    @Test fun fourRealCrashesStillRaiseTheAlert() {
        // The other half of the bargain: quietening the holds must not quieten
        // the thing the alert exists for.
        var consecutiveFailures = 0
        var alerted = false
        var alertsRaised = 0

        repeat(4) {
            val exit = ListenerExit.classify(
                stopRequested = false,
                stoppedOnPurpose = false,
                ephemeral = false,
                exitCode = 1,
            )
            if (ListenerExit.countsAsFailure(exit)) {
                val record = AlertPolicy.recordFailure(
                    AlertPolicy.Failure.LISTENER,
                    consecutiveFailures,
                    alerted,
                )
                consecutiveFailures = record.consecutiveFailures
                alerted = record.alerted
                if (record.alertNow) alertsRaised++
            }
        }

        assertEquals(4, consecutiveFailures)
        assertEquals(1, alertsRaised)
    }

    @Test fun aServiceStopOutranksEverythingElse() {
        // Nothing to restart and nothing to report: the runner is going away.
        assertEquals(
            ListenerExit.Kind.SERVICE_STOP,
            ListenerExit.classify(
                stopRequested = true,
                stoppedOnPurpose = true,
                ephemeral = true,
                exitCode = 0,
            ),
        )
    }

    @Test fun anEphemeralListenerThatFinishedItsJobStillComesStraightBack() {
        val exit = ListenerExit.classify(
            stopRequested = false,
            stoppedOnPurpose = false,
            ephemeral = true,
            exitCode = 0,
        )

        assertEquals(ListenerExit.Kind.JOB_COMPLETED, exit)
        assertTrue(ListenerExit.clearsBackoff(exit))
        assertFalse(ListenerExit.countsAsFailure(exit))
    }

    @Test fun anEphemeralListenerThatDiedInsteadIsNotAFinishedJob() {
        assertEquals(
            ListenerExit.Kind.FAILURE,
            ListenerExit.classify(
                stopRequested = false,
                stoppedOnPurpose = false,
                ephemeral = true,
                exitCode = 1,
            ),
        )
    }
}
