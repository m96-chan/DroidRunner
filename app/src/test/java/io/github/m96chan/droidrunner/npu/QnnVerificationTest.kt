package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnVerificationTest {

    /** What the NX769J actually returned once the Hexagon was working. */
    private val realRun = """
        {"ok":true,"model":"verification.tflite","requestedDevice":"qnn-htp",
         "backend":"htp","iterations":20,"avgUs":1369.02,"medianUs":1224.583,
         "delegation":{"delegated":64,"total":64,"partitions":1,
                       "describe":"all 64 operators on the Hexagon, 1 partitions",
                       "partial":false}}
    """.trimIndent()

    @Test fun aRunOnTheHexagonEarnsTheLabel() {
        val verdict = verdictFrom(realRun)

        assertTrue(verdict.verified)
        assertEquals(setOf("npu-qnn"), verdict.labels)
        assertEquals(64, verdict.delegated)
        assertTrue(verdict.detail, verdict.detail.contains("all 64 operators"))
        assertTrue(verdict.detail, verdict.detail.contains("1.22ms"))
    }

    @Test fun aRefusedRunEarnsNothingAndSaysWhy() {
        val verdict = verdictFrom(
            """{"ok":false,"error":"the delegate took no operators"}""",
        )

        assertFalse(verdict.verified)
        assertEquals(emptySet<String>(), verdict.labels)
        assertEquals("the delegate took no operators", verdict.detail)
    }

    @Test fun aRunThatDoesNotSayWhatExecutedItIsNotEvidence() {
        // The runner already refuses these, but this is where a mistake would
        // become a label other people select on, so it is checked again rather
        // than trusted.
        val verdict = verdictFrom("""{"ok":true,"medianUs":1200.0}""")

        assertFalse(verdict.verified)
        assertTrue(verdict.detail.contains("which processor"))
    }

    @Test fun zeroDelegatedOperatorsIsACpuRunWhateverElseItSays() {
        val verdict = verdictFrom(
            """{"ok":true,"medianUs":4400.0,"delegation":{"delegated":0,"total":64}}""",
        )

        assertFalse(verdict.verified)
        assertEquals("no operator ran on the Hexagon", verdict.detail)
    }

    @Test fun nonsenseIsNotEvidenceEither() {
        assertFalse(verdictFrom("segmentation fault").verified)
        assertFalse(verdictFrom("").verified)
    }

    @Test fun partialDelegationStillCounts() {
        // Some operators on the CPU is normal and still means the accelerator
        // did real work; the split is reported rather than hidden.
        val verdict = verdictFrom(
            """{"ok":true,"medianUs":2000.0,
                "delegation":{"delegated":31,"total":64,
                              "describe":"31 of 64 operators on the Hexagon"}}""",
        )

        assertTrue(verdict.verified)
        assertTrue(verdict.detail.contains("31 of 64"))
    }
}
