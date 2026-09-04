package io.github.m96chan.droidrunner.qnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnDelegationTest {

    private val fullyDelegated =
        "INFO: [Qnn Delegate] QNN delegate: 47 nodes delegated out of 47 nodes with 1 partitions."

    @Test fun theDelegatesOwnStatementIsWhatIsRead() {
        val delegation = QnnDelegation.parse(fullyDelegated)!!

        assertEquals(47, delegation.delegated)
        assertEquals(47, delegation.total)
        assertEquals(1, delegation.partitions)
        assertFalse(delegation.partial)
        assertEquals("all 47 operators on the Hexagon, 1 partitions", delegation.describe())
    }

    @Test fun theLastStatementWins() {
        // Warmup and the timed run each apply the delegate, and a line further
        // up the log may belong to a previous model entirely.
        val log = """
            INFO: [Qnn Delegate] QNN delegate: 12 nodes delegated out of 47 nodes with 3 partitions.
            INFO: [Qnn Delegate] QNN delegate: 47 nodes delegated out of 47 nodes with 1 partitions.
        """.trimIndent()

        assertEquals(47, QnnDelegation.parse(log)!!.delegated)
    }

    @Test fun aPartialSplitSaysWhereEachHalfRan() {
        val delegation = QnnDelegation.parse(
            "QNN delegate: 31 nodes delegated out of 47 nodes with 4 partitions.",
        )!!

        assertTrue(delegation.partial)
        assertFalse(delegation.none)
        assertEquals(
            "31 of 47 operators on the Hexagon, 16 on the CPU, 4 partitions",
            delegation.describe(),
        )
    }

    @Test fun takingNothingIsRefusedRatherThanTimed() {
        // Zero delegated nodes means everything ran on the CPU. Publishing that
        // latency as an NPU result is the failure this stage exists to stop.
        val delegation = QnnDelegation.parse(
            "QNN delegate: 0 nodes delegated out of 47 nodes with 0 partitions.",
        )!!

        assertTrue(delegation.none)
        val refusal = refuseUnattributable(delegation, profilingBytes = 0)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("no operators"))
    }

    @Test fun sayingNothingAtAllIsAlsoARefusal() {
        // If the delegate never reported and nothing was profiled, we do not
        // know which processor produced the number — and not knowing is the
        // thing that must never be published as a result.
        assertNotNull(refuseUnattributable(null, profilingBytes = 0))
        assertNotNull(refuseUnattributable(null, profilingBytes = -1))
    }

    @Test fun profilingAloneIsEnoughToAttributeARun() {
        // Nothing is recorded unless a QNN graph executed, so a quiet log with
        // profiling data still attributes the work.
        assertNull(refuseUnattributable(null, profilingBytes = 4096))
    }

    @Test fun aFullOrPartialRunIsPublished() {
        assertNull(refuseUnattributable(QnnDelegation(47, 47, 1), profilingBytes = 0))
        assertNull(refuseUnattributable(QnnDelegation(31, 47, 4), profilingBytes = 0))
    }

    @Test fun logsWithNoSuchLineParseToNothing() {
        assertNull(QnnDelegation.parse(""))
        assertNull(QnnDelegation.parse("INFO: [Qnn Delegate] initialising HTP backend"))
    }
}

class QnnOptionsTest {

    @Test fun theDelegateIsGivenNumbersBecauseThatIsWhatItParses() {
        // The plugin entry point parses each value as the integer of the
        // matching enum. "htp" becomes atoi("htp") — zero, kUndefinedBackend —
        // and the delegate then walks into a backend that does not exist and
        // takes the process down with it. That is not hypothetical: it is what
        // the first attempt did, and a native crash was the only symptom.
        val options = QnnOptions.forRun("htp")!!

        assertEquals("2", options["backend_type"])
        options.values.forEach { assertTrue(it, it.toIntOrNull() != null) }
    }

    @Test fun logLevelIsNeverSentBecauseItLosesTheBackend() {
        // Bisected on hardware: backend_type alone works, and with profiling or
        // htp_performance_mode it still works. Add log_level and the delegate
        // answers "requires valid backend". The delegate logs at INFO anyway.
        assertNull(QnnOptions.forRun("htp")!!["log_level"])
    }

    @Test fun profilingIsOnAsTheSecondOpinion() {
        // kBasicProfiling. Nothing is recorded unless a graph really ran on
        // the backend, which is what attributes a run on a device whose ROM
        // has no working logcat.
        assertEquals("1", QnnOptions.forRun("htp")!!["profiling"])
    }

    @Test fun onlyTheHtpGetsAnHtpPerformanceMode() {
        assertEquals("2", QnnOptions.forRun("htp")!!["htp_performance_mode"])
        assertNull(QnnOptions.forRun("gpu")!!["htp_performance_mode"])
    }

    @Test fun somethingThatIsNotAQnnBackendGetsNoOptions() {
        assertNull(QnnOptions.forRun("edgetpu"))
        assertNull(QnnOptions.backendCode("edgetpu"))
    }
}
