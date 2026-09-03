package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RunnerStatusTest {
    @Before fun reset() {
        RunnerStatus.reset()
    }

    @Test fun tracksListenerLifecycle() {
        RunnerStatus.onServiceStarted()
        assertEquals(RunnerState.STARTING, RunnerStatus.snapshot.value.state)

        RunnerStatus.onLogLine("2024-01-01 00:00:00Z: Listening for Jobs")
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)

        RunnerStatus.onLogLine("2024-01-01 00:01:00Z: Running job: build-arm64")
        assertEquals(RunnerState.JOB_RUNNING, RunnerStatus.snapshot.value.state)
        assertEquals("build-arm64", RunnerStatus.snapshot.value.currentJob)

        RunnerStatus.onLogLine("2024-01-01 00:05:00Z: Job build-arm64 completed with result: Succeeded")
        assertEquals(RunnerState.LISTENING, RunnerStatus.snapshot.value.state)
        assertNull(RunnerStatus.snapshot.value.currentJob)
        assertEquals(1, RunnerStatus.snapshot.value.jobsSucceeded)
        assertEquals(0, RunnerStatus.snapshot.value.jobsFailed)
    }

    @Test fun countsFailedJobs() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onLogLine("Running job: flaky")
        RunnerStatus.onLogLine("Job flaky completed with result: Failed")
        assertEquals(1, RunnerStatus.snapshot.value.jobsFailed)
    }

    @Test fun notifiesJobBoundariesOnce() {
        val transitions = mutableListOf<Boolean>()
        RunnerStatus.setJobBoundaryListener { transitions += it }
        try {
            RunnerStatus.onServiceStarted()
            RunnerStatus.onLogLine("Listening for Jobs")
            assertEquals(emptyList<Boolean>(), transitions)

            RunnerStatus.onLogLine("Running job: build")
            RunnerStatus.onLogLine("some job output")
            assertEquals(listOf(true), transitions)

            RunnerStatus.onLogLine("Job build completed with result: Succeeded")
            assertEquals(listOf(true, false), transitions)
        } finally {
            RunnerStatus.setJobBoundaryListener(null)
        }
    }

    @Test fun stopResetsStateButKeepsLog() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onLogLine("Listening for Jobs")
        RunnerStatus.onServiceStopped()
        val snapshot = RunnerStatus.snapshot.value
        assertEquals(RunnerState.STOPPED, snapshot.state)
        assertNull(snapshot.startedAtMillis)
        assertEquals(listOf("Listening for Jobs"), snapshot.recentLog)
    }

    @Test fun jobTotalsSurviveStartingTheServiceAgain() {
        RunnerStatus.onServiceStarted()
        RunnerStatus.onLogLine("Job build completed with result: Succeeded")
        RunnerStatus.onLogLine("Job flaky completed with result: Failed")

        RunnerStatus.onServiceStopped()
        RunnerStatus.onServiceStarted()

        assertEquals(1, RunnerStatus.snapshot.value.jobsSucceeded)
        assertEquals(1, RunnerStatus.snapshot.value.jobsFailed)

        RunnerStatus.onLogLine("Job build completed with result: Succeeded")
        assertEquals(2, RunnerStatus.snapshot.value.jobsSucceeded)
    }

    @Test fun addsToStoredTotalsInsteadOfWritingAResetOverThem() {
        val stored = FakeCounterStore(succeeded = 7, failed = 2)
        RunnerStatus.useCounterStore(stored)

        RunnerStatus.onServiceStarted()
        RunnerStatus.onLogLine("Job build completed with result: Succeeded")

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
