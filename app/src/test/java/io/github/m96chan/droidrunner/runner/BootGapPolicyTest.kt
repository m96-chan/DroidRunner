package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.runner.BootGapPolicy.BootRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootGapPolicyTest {

    private val minutes = 60_000L

    @Test fun theFirstStartOfABootIsTheOneThatMeasuresTheWait() {
        val first = BootGapPolicy.record(stored = null, bootId = "boot-a", uptimeMs = 40 * minutes)
        assertEquals(BootRecord("boot-a", 40 * minutes), first)

        // Someone opened the app and pressed Start again an hour later; the
        // device was still available all along, so the first figure stands.
        val second = BootGapPolicy.record(first, bootId = "boot-a", uptimeMs = 100 * minutes)
        assertEquals(first, second)
    }

    @Test fun aNewBootStartsMeasuringAgain() {
        val previous = BootRecord("boot-a", 40 * minutes)
        assertEquals(
            BootRecord("boot-b", 5 * minutes),
            BootGapPolicy.record(previous, bootId = "boot-b", uptimeMs = 5 * minutes),
        )
    }

    @Test fun aDeviceThatCameUpByItselfReportsNothing() {
        // Reaching BOOT_COMPLETED takes the better part of a minute even when
        // nobody is in the way, and that is not an outage.
        val record = BootGapPolicy.record(null, "boot-a", uptimeMs = 50_000)
        assertNull(BootGapPolicy.unattendedGapMs(record, "boot-a", startOnBootEnabled = true))
    }

    @Test fun aDeviceThatSatLockedReportsHowLong() {
        val record = BootGapPolicy.record(null, "boot-a", uptimeMs = 47 * minutes)
        assertEquals(
            47 * minutes,
            BootGapPolicy.unattendedGapMs(record, "boot-a", startOnBootEnabled = true),
        )
    }

    @Test fun aRecordFromAnEarlierBootSaysNothingAboutThisOne() {
        // The runner has not started yet on the boot now running, so there is
        // no gap to report — only one still being lived through.
        val record = BootRecord("boot-a", 47 * minutes)
        assertNull(BootGapPolicy.unattendedGapMs(record, "boot-b", startOnBootEnabled = true))
    }

    @Test fun nothingIsReportedBeforeTheRunnerHasEverStarted() {
        assertNull(BootGapPolicy.unattendedGapMs(null, "boot-a", startOnBootEnabled = true))
    }

    @Test fun withStartOnBootOffTheWaitIsTheConfigurationNotAGap() {
        // The device was never going to start on its own; reporting that as
        // downtime would nag about a choice the user already made.
        val record = BootGapPolicy.record(null, "boot-a", uptimeMs = 3 * 24 * 60 * minutes)
        assertNull(BootGapPolicy.unattendedGapMs(record, "boot-a", startOnBootEnabled = false))
    }

    @Test fun aClockThatWentBackwardsIsNotAGapEither() {
        // elapsedRealtime should never be negative, but a stored zero beats
        // reporting a nonsense duration if it ever is.
        val record = BootGapPolicy.record(null, "boot-a", uptimeMs = -1)
        assertEquals(0, record.startedAfterBootMs)
        assertNull(BootGapPolicy.unattendedGapMs(record, "boot-a", startOnBootEnabled = true))
    }

    @Test fun durationsReadAsHistoryRatherThanAsAStopwatch() {
        assertEquals("12m", BootGapPolicy.describe(12 * minutes + 44_000))
        assertEquals("2h 41m", BootGapPolicy.describe(161 * minutes))
        assertEquals("1d 3h", BootGapPolicy.describe(27 * 60 * minutes))
    }
}
