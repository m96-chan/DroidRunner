package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.runner.RunnerSnapshot
import io.github.m96chan.droidrunner.runner.RunnerState
import org.junit.Assert.assertEquals
import org.junit.Test

class RunnerLabelTest {

    @Test fun aHeldSessionIsNamedInsteadOfBeingCalledStarting() {
        // "starting" is what the screen also says when a device cannot start
        // at all, so an update's minute of waiting looked like trouble (#79).
        val snapshot = RunnerSnapshot(
            state = RunnerState.STARTING,
            sessionHeldSince = 1_000_000L,
        )

        assertEquals(
            "waiting for GitHub to release the previous session (30s)",
            snapshot.label(nowMillis = 1_030_000L),
        )
    }

    @Test fun everyOtherStateReadsAsItAlwaysDid() {
        assertEquals("listening for jobs", RunnerSnapshot(state = RunnerState.LISTENING).label())
        assertEquals("starting", RunnerSnapshot(state = RunnerState.STARTING).label())
        assertEquals("stopped", RunnerSnapshot(state = RunnerState.STOPPED).label())
    }

    @Test fun theSentenceGoesAwayWhenTheListenerGetsThrough() {
        val listening = RunnerSnapshot(state = RunnerState.LISTENING, sessionHeldSince = null)

        assertEquals("listening for jobs", listening.label())
    }
}
