package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An ephemeral registration serves one job, and the next launch must register
 * again. A phone looped instead: the runner updated itself mid-job, restarted,
 * found GitHub had deleted its registration, exited 0 and left `.runner`
 * behind, so the app relaunched the same dead identity every five seconds.
 */
class EphemeralIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    /** The listener's own lines from that phone, for the process that looped. */
    private val selfUpdateRun = listOf(
        "√ Connected to GitHub",
        "Current runner version: '2.337.0'",
        "2026-11-26 11:47:37Z: Listening for Jobs",
        "2026-11-26 11:48:09Z: Running job: model",
        "Runner update in progress, do not shutdown runner.",
        "Downloading 2.338.2 runner",
        "Waiting for current job finish running.",
        "2026-11-26 11:48:46Z: Job model completed with result: Failed",
        "Generate and execute update script.",
        "Runner will exit shortly for update, should be back online within 10 seconds.",
        "Runner update process finished.",
        "√ Connected to GitHub",
        "Runner listener exit because of updating, re-launch runner after successful update",
        "Update finished successfully.",
        "Restarting runner...",
        "√ Connected to GitHub",
        "Failed to create a session. The runner registration has been deleted from the server, " +
            "please re-configure. Runner registrations are automatically deleted for runners that " +
            "have not connected to the service recently.",
        "Runner listener exit with terminated error, stop the service, no retry needed.",
        "Exiting runner...",
    )

    /** What every launch after it printed, before the fix. */
    private val staleIdentityRun = listOf(
        "√ Connected to GitHub",
        selfUpdateRun[16],
        "Runner listener exit with terminated error, stop the service, no retry needed.",
        "Exiting runner...",
    )

    private fun transcriptOf(lines: List<String>) = ListenerTranscript().apply { lines.forEach(::onLine) }

    private fun runtimeWithIdentity(): File = temp.newFolder("runtime").apply {
        val home = File(this, "home/runner").apply { mkdirs() }
        listOf(".runner", ".credentials", ".credentials_rsaparams").forEach { File(home, it).writeText("{}") }
        File(home, "run.sh").writeText("#!/bin/sh")
    }

    private fun exitOf(transcript: ListenerTranscript, identityGone: Boolean, ephemeral: Boolean = true) =
        ListenerExit.classify(
            stopRequested = false,
            stoppedOnPurpose = false,
            ephemeral = ephemeral,
            exitCode = 0,
            jobCompleted = transcript.jobCompleted,
            registrationDeleted = transcript.registrationDeleted,
            identityGone = identityGone,
        )

    private fun spent(transcript: ListenerTranscript, current: Boolean = true, ephemeral: Boolean = true, exitCode: Int = 0) =
        ListenerExit.identitySpent(
            current = current,
            ephemeral = ephemeral,
            exitCode = exitCode,
            jobCompleted = transcript.jobCompleted,
            registrationDeleted = transcript.registrationDeleted,
        )

    @Test fun theSelfUpdateFromThePhoneEndsInARegistrationNotALoop() {
        val runtime = runtimeWithIdentity()

        // First launch: the job finished, then the update restart was refused.
        val first = transcriptOf(selfUpdateRun)
        assertTrue(first.jobCompleted)
        assertTrue(spent(first))
        RunnerRegistration.clearLocalRegistration(runtime)
        val gone = !RunnerRegistration.isRegistered(runtime)
        assertTrue(gone)
        assertEquals(ListenerExit.Kind.JOB_COMPLETED, exitOf(first, identityGone = gone))

        // Second launch: no evidence inherited, and nothing left to reuse, so
        // launchListener's `!isRegistered` sends it through registration.
        assertFalse(ListenerTranscript().jobCompleted)
        assertFalse(RunnerRegistration.isRegistered(runtime))
        assertTrue(File(runtime, "home/runner/run.sh").isFile)
    }

    @Test fun aPhoneAlreadyStuckWithADeadIdentityRecoversWithABackoff() {
        val runtime = runtimeWithIdentity()
        val run = transcriptOf(staleIdentityRun)

        assertFalse(run.jobCompleted)
        assertTrue(spent(run))
        RunnerRegistration.clearLocalRegistration(runtime)
        val exit = exitOf(run, identityGone = !RunnerRegistration.isRegistered(runtime))

        // Re-registers on the next launch, but after the backoff: a refusal
        // that repeats must not become a registration every five seconds.
        assertEquals(ListenerExit.Kind.FAILURE, exit)
        assertTrue(ListenerExit.countsAsFailure(exit))
        assertFalse(RunnerRegistration.isRegistered(runtime))
    }

    @Test fun aRefusalTheRunnerRecoveredFromIsForgotten() {
        val run = transcriptOf(
            listOf(selfUpdateRun[16], "2026-11-26 12:05:23Z: Listening for Jobs"),
        )
        assertFalse(run.registrationDeleted)
        assertFalse(spent(run))
    }

    @Test fun aLateThreadAfterAReplacementClearsNothing() {
        // A stop took the handle and a replacement may already have written a
        // fresh identity; only the current listener may spend one.
        assertFalse(spent(transcriptOf(selfUpdateRun), current = false))
    }

    @Test fun aJobTheRunnerCleanedUpAfterItselfIsServedEvenWithoutItsResultLine() {
        // The runner removed `.runner` on its own: nothing stale to relaunch.
        val run = transcriptOf(listOf("√ Connected to GitHub", "Exiting runner..."))
        assertFalse(spent(run))
        assertEquals(ListenerExit.Kind.JOB_COMPLETED, exitOf(run, identityGone = true))
    }

    @Test fun anIdentityLeftBehindWithoutEvidenceBacksOffInsteadOfLooping() {
        val run = transcriptOf(listOf("√ Connected to GitHub", "Exiting runner..."))
        val exit = exitOf(run, identityGone = false)
        assertEquals(ListenerExit.Kind.FAILURE, exit)
        assertFalse(ListenerExit.clearsBackoff(exit))
    }

    @Test fun aPersistentRegistrationGitHubDeletedIsClearedToo() {
        val run = transcriptOf(staleIdentityRun)
        assertTrue(spent(run, ephemeral = false))
        assertEquals(ListenerExit.Kind.FAILURE, exitOf(run, identityGone = true, ephemeral = false))
    }

    @Test fun aPersistentListenerKeepsItsIdentityAfterAJob() {
        assertFalse(spent(transcriptOf(selfUpdateRun.take(8)), ephemeral = false))
    }

    @Test fun anEphemeralListenerThatCrashedAfterItsJobKeepsItForTheRefusalToClear() {
        // Not exit 0: the next launch's "registration has been deleted" is the
        // evidence that clears it, behind a backoff.
        assertFalse(spent(transcriptOf(selfUpdateRun.take(8)), exitCode = 1))
    }

    @Test fun deliberateStopsStillOutrankTheEvidence() {
        val run = transcriptOf(selfUpdateRun)
        listOf(true to false, false to true).forEach { (stopRequested, stoppedOnPurpose) ->
            val exit = ListenerExit.classify(
                stopRequested = stopRequested,
                stoppedOnPurpose = stoppedOnPurpose,
                ephemeral = true,
                exitCode = 0,
                jobCompleted = run.jobCompleted,
                registrationDeleted = run.registrationDeleted,
                identityGone = true,
            )
            assertFalse(ListenerExit.countsAsFailure(exit))
            assertTrue(exit == ListenerExit.Kind.SERVICE_STOP || exit == ListenerExit.Kind.POLICY_STOP)
        }
    }
}
