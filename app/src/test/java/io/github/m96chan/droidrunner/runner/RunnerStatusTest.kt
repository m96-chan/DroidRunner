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
}
