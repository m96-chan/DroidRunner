package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationTest {

    private val fullyDelegated =
        "INFO: [Qnn Delegate] QNN delegate: 47 nodes delegated out of 47 nodes with 1 partitions."

    @Test fun theDelegatesOwnStatementIsWhatIsRead() {
        val delegation = Delegation.parse(fullyDelegated)!!

        assertEquals(47, delegation.delegated)
        assertEquals(47, delegation.total)
        assertEquals(1, delegation.partitions)
        assertFalse(delegation.partial)
        assertEquals(
            "all 47 operators on the Hexagon, 1 partitions",
            delegation.describe("the Hexagon"),
        )
    }

    @Test fun theLastStatementWins() {
        // Warmup and the timed run each apply the delegate, and a line further
        // up the log may belong to a previous model entirely.
        val log = """
            INFO: [Qnn Delegate] QNN delegate: 12 nodes delegated out of 47 nodes with 3 partitions.
            INFO: [Qnn Delegate] QNN delegate: 47 nodes delegated out of 47 nodes with 1 partitions.
        """.trimIndent()

        assertEquals(47, Delegation.parse(log)!!.delegated)
    }

    @Test fun aPartialSplitSaysWhereEachHalfRan() {
        val delegation = Delegation.parse(
            "QNN delegate: 31 nodes delegated out of 47 nodes with 4 partitions.",
        )!!

        assertTrue(delegation.partial)
        assertFalse(delegation.none)
        assertEquals(
            "31 of 47 operators on the Hexagon, 16 on the CPU, 4 partitions",
            delegation.describe("the Hexagon"),
        )
    }

    @Test fun takingNothingIsRefusedRatherThanTimed() {
        // Zero delegated nodes means everything ran on the CPU. Publishing that
        // latency as an NPU result is the failure this stage exists to stop.
        val delegation = Delegation.parse(
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
        assertNull(refuseUnattributable(Delegation(47, 47, 1), profilingBytes = 0))
        assertNull(refuseUnattributable(Delegation(31, 47, 4), profilingBytes = 0))
    }

    @Test fun logsWithNoSuchLineParseToNothing() {
        assertNull(Delegation.parse(""))
        assertNull(Delegation.parse("INFO: [Qnn Delegate] initialising HTP backend"))
    }
}

class TfLiteDelegationReportTest {

    @Test fun tfLitesOwnAnnouncementIsRead() {
        // What an NX769J actually printed once the delegate was created the way
        // Qualcomm's own wrapper creates it.
        val delegation = Delegation.parse(
            "VERBOSE: Replacing 64 out of 64 node(s) with delegate (TfLiteQnnDelegate) " +
                "node, yielding 1 partitions for the whole graph.",
        )!!

        assertEquals(64, delegation.delegated)
        assertEquals(64, delegation.total)
        assertEquals(1, delegation.partitions)
        assertTrue(delegation.describe("the Hexagon").startsWith("all 64 operators"))
    }

    @Test fun theDelegateThatTookTheNodesIsNamed() {
        // Which is the answer to "who ran the graph" — the whole of #93.
        val delegation = Delegation.parse(
            "INFO: Replacing 12 out of 14 node(s) with delegate (TfLiteNnapiDelegate) " +
                "node, yielding 2 partitions for the whole graph.",
        )!!

        assertEquals("TfLiteNnapiDelegate", delegation.delegate)
        assertEquals("partial", delegation.executed)
    }

    @Test fun anotherDelegatesLineCannotBeClaimedAsOurs() {
        // XNNPACK announces itself in exactly the same words. Reading its line
        // as the Hexagon's would attribute a CPU run to the accelerator, which
        // is the one thing that must never happen.
        val xnnpack = Delegation.parse(
            "INFO: Replacing 64 out of 64 node(s) with delegate (TfLiteXNNPackDelegate) " +
                "node, yielding 1 partitions for the whole graph.",
        )

        assertEquals("TfLiteXNNPackDelegate", xnnpack!!.delegate)
        assertNotNull(refuseUnattributable(xnnpack, 0, "TfLiteQnnDelegate"))
        assertNull(refuseUnattributable(xnnpack, 0, "TfLiteXNNPackDelegate"))
    }

    @Test fun executedSaysWhoDidTheWorkWithoutReadingTheTiming() {
        assertEquals("accelerator", Delegation(64, 64, 1).executed)
        assertEquals("partial", Delegation(31, 64, 4).executed)
        assertEquals("cpu-fallback", Delegation(0, 64, 0).executed)
    }

    @Test fun refusedOperatorsAreNamedSoATableCanBeCorrected() {
        // "12 of 14 nodes went to the accelerator" says an operator table is
        // wrong somewhere; this says where.
        val log = """
            Operator RESHAPE (v1) is not supported by the NNAPI delegate
            Operator PACK (v2) is not supported by the NNAPI delegate
            Operator RESHAPE (v1) is not supported by the NNAPI delegate
        """.trimIndent()

        assertEquals(listOf("RESHAPE", "PACK"), Delegation.unsupported(log))
    }
}


class DelegateNamingTest {

    @Test fun theReportNamesWhatActuallyTookTheNodes() {
        // The first version said "on the Hexagon" whatever ran the graph, and
        // reported a CPU run through nnapi-reference as 64 operators on an
        // accelerator. That is the claim this whole issue exists to stop.
        val nnapi = Delegation.parse(
            "Replacing 64 out of 64 node(s) with delegate (TfLiteNnapiDelegate) " +
                "node, yielding 1 partitions",
        )!!

        assertEquals("all 64 operators on TfLiteNnapiDelegate, 1 partitions", nnapi.describe())
    }

    @Test fun anUnnamedDelegateIsNotGivenAName() {
        assertEquals("all 8 operators on the delegate, 1 partitions", Delegation(8, 8, 1).describe())
    }
}

class ExecutedForTest {

    private fun nnapi(delegated: Int, total: Int) =
        Delegation(delegated, total, 1, "TfLiteNnapiDelegate")

    private fun xnnpack(delegated: Int, total: Int) =
        Delegation(delegated, total, 1, "TfLiteXNNPackDelegate")

    @Test fun aPinnedDriverThatRanItIsNamedWithTheDelegate() {
        val (executed, by) = executedFor(nnapi(64, 64), "mtk-neuron_shim")

        assertEquals("accelerator", executed)
        assertEquals("TfLiteNnapiDelegate:mtk-neuron_shim", by)
    }

    @Test fun aCpuDelegateIsNeverAnAcceleratorHoweverItWasReached() {
        // Pinning to mtk-mdla_shim and having XNNPACK take the graph means the
        // NNAPI delegate refused and the CPU picked it up. The first version
        // of this reported that as accelerator on mtk-mdla_shim, which is the
        // exact claim #93 exists to stop. Caught by running on a second vendor.
        val (executed, by) = executedFor(xnnpack(64, 64), "mtk-mdla_shim")

        assertEquals("cpu-fallback", executed)
        assertEquals("TfLiteXNNPackDelegate", by)
    }

    @Test fun withNothingRequestedTheCpuIsJustTheCpu() {
        assertEquals("cpu" to "TfLiteXNNPackDelegate", executedFor(xnnpack(60, 64), null))
        assertEquals("cpu" to "cpu", executedFor(null, null))
    }

    @Test fun aPartialAcceleratorRunSaysPartial() {
        val (executed, by) = executedFor(nnapi(31, 64), "google-edgetpu")

        assertEquals("partial", executed)
        assertEquals("TfLiteNnapiDelegate:google-edgetpu", by)
    }

    @Test fun takingNothingIsAFallbackWhateverTheDelegateWasCalled() {
        assertEquals("cpu-fallback" to "cpu", executedFor(nnapi(0, 64), "mtk-dsp_shim"))
    }

    @Test fun aRequestedDeviceWithNoReportAtAllIsUnknownRatherThanFine() {
        // Silence is not a measurement. Saying "accelerator" here would be a
        // guess wearing the clothes of a result.
        assertEquals("unknown" to "cpu", executedFor(null, "mtk-neuron_shim"))
    }
}

class CpuDriverTest {

    @Test fun nnapisReferenceDriverIsTheCpuAndSaysSo() {
        // It is the CPU by name and by nature. Calling a 257ms run on it an
        // accelerator result is the same wrong claim as calling XNNPACK one,
        // and it was in the first version for the same reason.
        val (executed, by) = executedFor(
            Delegation(64, 64, 1, "TfLiteNnapiDelegate"),
            "nnapi-reference",
        )

        assertEquals("cpu", executed)
        assertEquals("TfLiteNnapiDelegate:nnapi-reference", by)
    }

    @Test fun arealAcceleratorThroughTheSameDelegateIsStillAnAccelerator() {
        assertEquals(
            "accelerator",
            executedFor(Delegation(64, 64, 1, "TfLiteNnapiDelegate"), "mtk-mdla_shim").first,
        )
    }
}
