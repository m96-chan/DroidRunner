package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.monitor.SystemSnapshot
import io.github.m96chan.droidrunner.runner.RunnerSnapshot
import io.github.m96chan.droidrunner.runner.RunnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipScreenTest {

    @Test fun aWorkingRunnerIsWorthLeavingOnScreen() {
        assertTrue(shouldOfferPip(supported = true, showingSetup = false, RunnerState.LISTENING))
        assertTrue(shouldOfferPip(supported = true, showingSetup = false, RunnerState.JOB_RUNNING))
        // Held is the state people most want to keep an eye on.
        assertTrue(shouldOfferPip(supported = true, showingSetup = false, RunnerState.PAUSED))
    }

    @Test fun aStoppedRunnerLeavesNoWindowBehind() {
        assertFalse(shouldOfferPip(supported = true, showingSetup = false, RunnerState.STOPPED))
    }

    @Test fun setupIsNotShrunkToAQuarterOfTheScreen() {
        assertFalse(shouldOfferPip(supported = true, showingSetup = true, RunnerState.LISTENING))
    }

    @Test fun devicesWithoutPictureInPictureAreNeverOfferedIt() {
        // FEATURE_PICTURE_IN_PICTURE is not guaranteed present, and asking for
        // it where it is missing throws.
        assertFalse(shouldOfferPip(supported = false, showingSetup = false, RunnerState.LISTENING))
        assertFalse(shouldOfferPip(supported = false, showingSetup = false, RunnerState.JOB_RUNNING))
    }

    @Test fun theStatsLineFitsWhatTheWindowCanShow() {
        val system = SystemSnapshot(cpuAverage = 0.214f, batteryPercent = 93, charging = true)
        assertEquals("cpu 21% · bat 93%⚡ · ok:12 fail:1", pipStats(system, succeeded = 12, failed = 1))
    }

    @Test fun anUnpluggedDeviceSaysSoByOmission() {
        val system = SystemSnapshot(cpuAverage = 0f, batteryPercent = 40, charging = false)
        assertEquals("cpu 0% · bat 40% · ok:0 fail:0", pipStats(system, succeeded = 0, failed = 0))
    }

    @Test fun aHeldRunnerSaysWhyItIsHeld() {
        // Held and broken look identical from outside; this line is the only
        // place the window can tell them apart.
        val detail = pipDetail(
            RunnerSnapshot(state = RunnerState.PAUSED, pausedReason = "not charging"),
        )
        assertEquals("not charging", detail?.text)
        assertTrue("and it is marked as a warning", detail!!.isCondition)
    }

    @Test fun aWarningDoesNotBlankOutTheJobItHasNotStopped() {
        // Since #37 an admission warning can stand while the listener keeps
        // working. The build's name is then the more useful fact — showing the
        // condition instead would hide a job that is still running, in plain
        // text, where a job name is expected.
        val detail = pipDetail(
            RunnerSnapshot(
                state = RunnerState.JOB_RUNNING,
                currentJob = "build (arm64)",
                pausedReason = "battery 25% below 30%",
            ),
        )
        assertEquals("build (arm64)", detail?.text)
        assertFalse(detail!!.isCondition)
    }

    @Test fun aWarningWithNoJobToNameIsShownAsAWarning() {
        val detail = pipDetail(
            RunnerSnapshot(state = RunnerState.LISTENING, pausedReason = "cooling down (thermal severe)"),
        )
        assertEquals("cooling down (thermal severe)", detail?.text)
        assertTrue(detail!!.isCondition)
    }

    @Test fun anIdleRunnerHasNothingToSayOnThatLine() {
        assertNull(pipDetail(RunnerSnapshot(state = RunnerState.LISTENING)))
    }
}
