package io.github.m96chan.droidrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunnerLogTest {

    @get:Rule val folder = TemporaryFolder()

    private fun log(maxBytes: Long = RunnerLog.MAX_BYTES) =
        RunnerLog(folder.root, maxBytes)

    private fun currentLog() = File(folder.root, RunnerLog.FILE_NAME)

    private fun previousLog() = File(folder.root, RunnerLog.PREVIOUS_FILE_NAME)

    @Test fun rotatesOnlyOnceTheCapIsReached() {
        assertFalse(RunnerLog.shouldRotate(currentBytes = 0, maxBytes = 100))
        assertFalse(RunnerLog.shouldRotate(currentBytes = 99, maxBytes = 100))
        assertTrue(RunnerLog.shouldRotate(currentBytes = 100, maxBytes = 100))
        assertTrue(RunnerLog.shouldRotate(currentBytes = 4_096, maxBytes = 100))
    }

    @Test fun aNewAttemptKeepsWhatTheLastOneWrote() {
        log().use { it.append("first attempt") }
        log().use { it.append("second attempt") }

        val lines = currentLog().readLines()
        assertTrue(lines.contains("first attempt"))
        assertTrue(lines.contains("second attempt"))
    }

    @Test fun eachAttemptAnnouncesItself() {
        log().use { it.startAttempt(0) }
        log().use { it.startAttempt(1_000) }

        val headers = currentLog().readLines().filter { it.startsWith("=== listener started") }
        assertEquals(
            listOf(
                "=== listener started 1970-01-01T00:00:00Z ===",
                "=== listener started 1970-01-01T00:00:01Z ===",
            ),
            headers,
        )
    }

    @Test fun aLineIsReadableBeforeTheWriterIsClosed() {
        // The process that dies is usually the one holding the log open, so
        // anything buffered at that moment would never reach the file.
        log().use {
            it.append("proot info: vpid 1: terminated with signal 15")
            assertEquals(
                listOf("proot info: vpid 1: terminated with signal 15"),
                currentLog().readLines(),
            )
        }
    }

    @Test fun growingPastTheCapRollsTheFileAside() {
        val line = "x".repeat(50)
        log(maxBytes = 200).use { log -> repeat(10) { log.append(line) } }

        assertTrue(previousLog().length() >= 200)
        assertTrue(currentLog().length() < 200)
        assertTrue(currentLog().readLines().contains(line))
    }

    @Test fun onlyOneGenerationIsKeptSoTheLogStaysBounded() {
        val line = "y".repeat(50)
        log(maxBytes = 200).use { log -> repeat(100) { log.append(line) } }

        assertEquals(
            listOf(RunnerLog.FILE_NAME, RunnerLog.PREVIOUS_FILE_NAME),
            folder.root.list()!!.sorted(),
        )
        assertTrue(currentLog().length() + previousLog().length() <= 2 * 200 + line.length + 1)
    }

    @Test fun aLongLivedListenerIsRotatedWithoutRestarting() {
        // Rotation used to be conceivable only at start-up, which is exactly
        // when the listener that fills the disk — one that never exits — is
        // not looking.
        log(maxBytes = 200).use { log ->
            repeat(20) { log.append("z".repeat(50)) }
            assertTrue(previousLog().exists())
        }
    }
}
