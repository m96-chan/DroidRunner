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
        log().use { it.append("first attempt", RunnerLog.Source.RUNNER) }
        log().use { it.append("second attempt", RunnerLog.Source.RUNNER) }

        val lines = currentLog().readLines()
        assertTrue(lines.contains("[runner] first attempt"))
        assertTrue(lines.contains("[runner] second attempt"))
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
            it.append("proot info: vpid 1: terminated with signal 15", RunnerLog.Source.RUNNER)
            assertEquals(
                listOf("[runner] proot info: vpid 1: terminated with signal 15"),
                currentLog().readLines(),
            )
        }
    }

    @Test fun growingPastTheCapRollsTheFileAside() {
        val line = "x".repeat(50)
        log(maxBytes = 200).use { log -> repeat(10) { log.append(line, RunnerLog.Source.RUNNER) } }

        assertTrue(previousLog().length() >= 200)
        assertTrue(currentLog().length() < 200)
        assertTrue(currentLog().readLines().contains("[runner] $line"))
    }

    @Test fun onlyOneGenerationIsKeptSoTheLogStaysBounded() {
        val line = "y".repeat(50)
        log(maxBytes = 200).use { log -> repeat(100) { log.append(line, RunnerLog.Source.RUNNER) } }

        assertEquals(
            listOf(RunnerLog.FILE_NAME, RunnerLog.PREVIOUS_FILE_NAME),
            folder.root.list()!!.sorted(),
        )
        // Each generation is capped, plus the line that carried it over.
        val entry = RunnerLog.format(RunnerLog.Source.RUNNER, line).length + 1
        assertTrue(currentLog().length() + previousLog().length() <= 2 * (200 + entry))
    }

    @Test fun everyLineSaysWhetherTheRunnerOrTheAppIsSpeaking() {
        // Diagnosing a night that went wrong is reading these two against each
        // other, so the file must not blur what the runner reported into what
        // the app decided.
        log().use {
            it.append("admission: not charging", RunnerLog.Source.APP)
            it.append("proot info: vpid 1: terminated with signal 15", RunnerLog.Source.RUNNER)
            it.append("recovery: listener exited with code 0", RunnerLog.Source.APP)
        }

        assertEquals(
            listOf(
                "[app] admission: not charging",
                "[runner] proot info: vpid 1: terminated with signal 15",
                "[app] recovery: listener exited with code 0",
            ),
            currentLog().readLines(),
        )
    }

    @Test fun aLineFromOneThreadIsNeverSplitByAnother() {
        // The supervisor, the output thread and the dashboard's callers all
        // write here; a line torn in half by another is a line nobody can read.
        val writers = 8
        val perWriter = 200
        val ready = java.util.concurrent.CountDownLatch(writers)
        val go = java.util.concurrent.CountDownLatch(1)

        log().use { log ->
            val threads = (0 until writers).map { writer ->
                Thread {
                    val source = if (writer % 2 == 0) RunnerLog.Source.APP else RunnerLog.Source.RUNNER
                    val line = "writer-$writer " + "$writer".repeat(500)
                    ready.countDown()
                    go.await()
                    repeat(perWriter) { log.append(line, source) }
                }.also { it.start() }
            }
            ready.await()
            go.countDown()
            threads.forEach { it.join() }
        }

        val expected = (0 until writers).associate { writer ->
            val source = if (writer % 2 == 0) RunnerLog.Source.APP else RunnerLog.Source.RUNNER
            RunnerLog.format(source, "writer-$writer " + "$writer".repeat(500)) to perWriter
        }
        val written = currentLog().readLines().groupingBy { it }.eachCount()
        assertEquals(expected, written)
    }

    @Test fun aLogThatCannotBeWrittenDoesNotStopTheRunner() {
        // A full disk costs the explanation and nothing else: throwing from a
        // log call would take down the thread reading the listener's output,
        // and with it the build that was running.
        val unwritable = RunnerLog(File(folder.root, "no/such/directory"))
        unwritable.use {
            it.startAttempt(0)
            it.append("admission: not charging", RunnerLog.Source.APP)
        }
    }

    @Test fun aLongLivedListenerIsRotatedWithoutRestarting() {
        // Rotation used to be conceivable only at start-up, which is exactly
        // when the listener that fills the disk — one that never exits — is
        // not looking.
        log(maxBytes = 200).use { log ->
            repeat(20) { log.append("z".repeat(50), RunnerLog.Source.RUNNER) }
            assertTrue(previousLog().exists())
        }
    }

    // --- the tail that goes into a bug report (issue #152) ------------------

    @Test fun theListenersBannerBoxIsNotWorthPasting() {
        assertTrue(RunnerLog.isDecoration("[runner] |                          |"))
        assertTrue(RunnerLog.isDecoration("[runner] --------------------------"))
        assertTrue(RunnerLog.isDecoration("[runner] ______"))
    }

    @Test fun anAttemptHeaderIsKeptBecauseARestartLoopIsTheFinding() {
        // A file that is nothing but restarts is the whole report in the cases
        // this exists for; dropping these would leave one long quiet run.
        assertFalse(RunnerLog.isDecoration(RunnerLog.attemptHeader(0L)))
    }

    @Test fun realLinesSurviveEvenWhenTheyContainDashesOrPipes() {
        assertFalse(RunnerLog.isDecoration("[runner] 2026-09-06T00:00:00Z: Listening for Jobs"))
        assertFalse(RunnerLog.isDecoration("[app] admission: held (not charging)"))
        assertFalse(RunnerLog.isDecoration(""))
    }

    @Test fun theTailKeepsTheEndAndNotTheBeginning() {
        val lines = (1..500).map { "[app] line $it" }

        val tail = RunnerLog.reportTail(lines, maxLines = 3)

        assertEquals(listOf("[app] line 498", "[app] line 499", "[app] line 500"), tail)
    }

    @Test fun bothGenerationsAreReadWithTheOlderOneFirst() {
        // A rotation that has just happened leaves the current file nearly
        // empty, which is not the moment to have nothing to say.
        previousLog().writeText("[app] older\n")
        currentLog().writeText("[app] newer\n")

        val tail = RunnerLog.readTail(folder.root)

        assertTrue(tail.indexOf("[app] older") < tail.indexOf("[app] newer"))
    }

    @Test fun readingTheTailOfNothingIsEmptyRatherThanAFailure() {
        // Someone filing a bug from a device that has never run one should get
        // a report without a log, not an exception raised while filing it.
        assertEquals(emptyList<String>(), RunnerLog.readTail(folder.root))
    }

    @Test fun seekingIntoTheMiddleDoesNotEmitHalfALine() {
        currentLog().writeText("[app] first line that will be cut\n[app] whole line\n")

        val tail = RunnerLog.readTail(folder.root, maxBytes = 20)

        assertFalse(tail.any { it.contains("first line") })
        assertTrue(tail.contains("[app] whole line"))
    }
}
