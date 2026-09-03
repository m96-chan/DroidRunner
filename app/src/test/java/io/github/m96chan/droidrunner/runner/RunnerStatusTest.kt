package io.github.m96chan.droidrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunnerStatusTest {

    @get:Rule val folder = TemporaryFolder()

    @Before fun reset() {
        RunnerStatus.reset()
    }

    @Test fun tracksListenerLifecycle() {
        RunnerStatus.onServiceStarted()
        assertEquals(RunnerState.STARTING, RunnerStatus.snapshot.value.state)

        RunnerStatus.onRunnerLine("2024-01-01 00:00:00Z: Listening for Jobs")
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)

        RunnerStatus.onRunnerLine("2024-01-01 00:01:00Z: Running job: build-arm64")
        assertEquals(RunnerState.JOB_RUNNING, RunnerStatus.snapshot.value.state)
        assertEquals("build-arm64", RunnerStatus.snapshot.value.currentJob)

        RunnerStatus.onRunnerLine("2024-01-01 00:05:00Z: Job build-arm64 completed with result: Succeeded")
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)
        assertNull(RunnerStatus.snapshot.value.currentJob)
        assertEquals(1, RunnerStatus.snapshot.value.jobsSucceeded)
        assertEquals(0, RunnerStatus.snapshot.value.jobsFailed)
    }

    @Test fun countsFailedJobs() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onRunnerLine("Running job: flaky")
        RunnerStatus.onRunnerLine("Job flaky completed with result: Failed")
        assertEquals(1, RunnerStatus.snapshot.value.jobsFailed)
    }

    @Test fun admissionWarningDoesNotChangeListenerStateAndRecoversEagerly() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onRunnerLine("Listening for Jobs")

        RunnerStatus.onConditionObserved("not charging")
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)
        assertEquals("not charging", RunnerStatus.snapshot.value.pausedReason)

        RunnerStatus.onConditionRecovered()
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)
        assertNull(RunnerStatus.snapshot.value.pausedReason)
    }

    @Test fun notifiesJobBoundariesOnce() {
        val transitions = mutableListOf<Boolean>()
        RunnerStatus.setJobBoundaryListener { transitions += it }
        try {
            RunnerStatus.onServiceStarted()
            RunnerStatus.onRunnerLine("Listening for Jobs")
            assertEquals(emptyList<Boolean>(), transitions)

            RunnerStatus.onRunnerLine("Running job: build")
            RunnerStatus.onRunnerLine("some job output")
            assertEquals(listOf(true), transitions)

            RunnerStatus.onRunnerLine("Job build completed with result: Succeeded")
            assertEquals(listOf(true, false), transitions)
        } finally {
            RunnerStatus.setJobBoundaryListener(null)
        }
    }

    @Test fun whatTheAppDecidedIsKeptBesideWhatTheRunnerSaid() {
        // The two halves of a diagnosis: the listener's output says what
        // happened, the app's lines say why it was made to happen. Only one of
        // them used to reach the disk (issue #52).
        RunnerLog(folder.root).use { log ->
            RunnerStatus.attachLog(log)
            RunnerStatus.onAppLine("admission: not charging")
            RunnerStatus.onListenerAttempt(0)
            RunnerStatus.onRunnerLine("Listening for Jobs")
            RunnerStatus.onRestarting("listener exited with code 0 — retrying in 5s")
        }

        assertEquals(
            listOf(
                "[app] admission: not charging",
                "",
                "=== listener started 1970-01-01T00:00:00Z ===",
                "[runner] Listening for Jobs",
                "[app] recovery: listener exited with code 0 — retrying in 5s",
            ),
            File(folder.root, RunnerLog.FILE_NAME).readLines(),
        )
    }

    @Test fun theDashboardsTailShowsLinesWithoutTheirFileTags() {
        // The tag is a property of the file, where two voices share one order;
        // the panel on screen is already labelled by being the runner panel.
        RunnerLog(folder.root).use { log ->
            RunnerStatus.attachLog(log)
            RunnerStatus.onAppLine("admission: not charging")
            RunnerStatus.onListenerAttempt(0)
        }

        assertEquals(listOf("admission: not charging"), RunnerStatus.snapshot.value.recentLog)
    }

    @Test fun stopResetsStateButKeepsLog() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onRunnerLine("Listening for Jobs")
        RunnerStatus.onServiceStopped()
        val snapshot = RunnerStatus.snapshot.value
        assertEquals(RunnerState.STOPPED, snapshot.state)
        assertNull(snapshot.startedAtMillis)
        assertEquals(listOf("Listening for Jobs"), snapshot.recentLog)
    }

    @Test fun jobTotalsSurviveStartingTheServiceAgain() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onRunnerLine("Job build completed with result: Succeeded")
        RunnerStatus.onRunnerLine("Job flaky completed with result: Failed")

        RunnerStatus.onServiceStopped()
        RunnerStatus.onServiceStarted()

        assertEquals(1, RunnerStatus.snapshot.value.jobsSucceeded)
        assertEquals(1, RunnerStatus.snapshot.value.jobsFailed)

        RunnerStatus.onRunnerLine("Job build completed with result: Succeeded")
        assertEquals(2, RunnerStatus.snapshot.value.jobsSucceeded)
    }

    @Test fun addsToStoredTotalsInsteadOfWritingAResetOverThem() {
        val stored = FakeCounterStore(succeeded = 7, failed = 2)
        RunnerStatus.useCounterStore(stored)

        RunnerStatus.onServiceStarted()
        RunnerStatus.onRunnerLine("Job build completed with result: Succeeded")

        assertEquals(8, stored.succeeded)
        assertEquals(2, stored.failed)
    }

    @Test fun countsSupervisorRestartsPerRunOfTheService() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onRestarting("listener exited")
        assertEquals(1, RunnerStatus.snapshot.value.restarts)

        RunnerStatus.onServiceStarted()
        assertEquals(0, RunnerStatus.snapshot.value.restarts)
    }

    /** Stands in for the SharedPreferences the app keeps the totals in. */
    private class FakeCounterStore(var succeeded: Int, var failed: Int) : JobCounterStore {
        override fun read(): Pair<Int, Int> = succeeded to failed

        override fun write(succeeded: Int, failed: Int) {
            this.succeeded = succeeded
            this.failed = failed
        }
    }
}
