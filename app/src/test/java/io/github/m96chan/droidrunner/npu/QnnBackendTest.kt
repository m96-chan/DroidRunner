package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QnnBackendTest {

    @Test fun qnnNamesAreRecognisedAndTheRestAreLeftToNnapi() {
        assertEquals("htp", QnnBackend.of("qnn-htp"))
        assertEquals("gpu", QnnBackend.of("qnn-gpu"))
        assertEquals("htp", QnnBackend.of("QNN-HTP"))

        assertNull(QnnBackend.of(null))
        assertNull(QnnBackend.of("google-edgetpu"))
        assertNull(QnnBackend.of("nnapi-reference"))
    }

    @Test fun aMisspeltQnnBackendFailsRatherThanFallingThroughToTheCpu() {
        // Falling through would send "qnn-hpt" to NNAPI, which on these phones
        // reaches only nnapi-reference — the CPU — and the job would get a
        // plausible number from the wrong processor.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            QnnBackend.of("qnn-hpt")
        }

        assertEquals(true, failure.message!!.contains("qnn-htp"))
    }

    @Test fun theOlderDspBackendIsNotOffered() {
        // It needs libQnnDsp.so, which this app does not fetch. Accepting the
        // name and failing later inside the delegate would be worse than
        // saying so here.
        assertThrows(IllegalArgumentException::class.java) { QnnBackend.of("qnn-dsp") }
    }
}
