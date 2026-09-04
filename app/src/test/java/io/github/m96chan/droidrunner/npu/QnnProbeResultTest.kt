package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnProbeResultTest {

    @Test fun aWorkingDeviceReportsItsHtpAndTheProcessThatAnswered() {
        val result = QnnProbeResult.parse(
            """
            {"pid":9123,
             "libraries":[{"name":"libQnnHtp.so","loaded":true},
                          {"name":"libQnnTFLiteDelegate.so","loaded":true}],
             "capabilities":{"htpQuant":1,"htpFp16":1,"gpu":0,"dsp":0},
             "ok":true}
            """.trimIndent(),
        )

        assertTrue(result.htpUsable)
        assertEquals(9123, result.pid)
        assertEquals(2, result.loaded.size)
        assertEquals(listOf("htpFp16", "htpQuant"), result.available)
        assertTrue(result.summary().startsWith("QNN loaded; Hexagon HTP available"))
    }

    @Test fun aLibraryThatWouldNotOpenIsNamedAlongWithTheReason() {
        // The reason is the whole value of reporting this: "cannot locate
        // symbol" and "no such file" send you to completely different places.
        val result = QnnProbeResult.parse(
            """
            {"pid":9123,
             "libraries":[{"name":"libQnnHtp.so","loaded":false,
                           "error":"dlopen failed: library \"libcdsprpc.so\" not found"}],
             "ok":false}
            """.trimIndent(),
        )

        assertFalse(result.htpUsable)
        assertEquals(setOf("libQnnHtp.so"), result.failed.keys)
        assertTrue(result.summary().contains("libcdsprpc.so"))
    }

    @Test fun theOlderDspBackendIsNotWhatMakesADeviceAccelerated() {
        // kCapDspRuntime names the legacy Hexagon DSP, reached through
        // libQnnDsp.so, which this app does not fetch. An 8 Gen 3 answers no
        // to it and yes to the HTP; reading the DSP answer as the verdict says
        // "no acceleration" about hardware that plainly accelerates.
        val onlyDsp = QnnProbeResult.parse(
            """{"ok":true,"capabilities":{"htpQuant":0,"htpFp16":0,"gpu":0,"dsp":1}}""",
        )
        val onlyHtp = QnnProbeResult.parse(
            """{"ok":true,"capabilities":{"htpQuant":1,"htpFp16":1,"gpu":0,"dsp":0}}""",
        )

        assertFalse(onlyDsp.htpUsable)
        assertTrue(onlyHtp.htpUsable)
    }

    @Test fun aDeviceThatSaysNoToEverythingSaysWhatItWasAsked() {
        // "no backend" and "the delegate never answered" look the same from
        // outside and are fixed differently, so the raw answers are reported.
        val result = QnnProbeResult.parse(
            """{"pid":1,"libraries":[],"capabilities":{"htpQuant":0,"dsp":0},"ok":true}""",
        )

        assertFalse(result.htpUsable)
        assertTrue(result.summary().contains("htpQuant=0"))
        assertTrue(result.summary().contains("dsp=0"))
    }

    @Test fun anAnswerThatIsNotJsonIsAFailureRatherThanACrash() {
        // The far side is a process holding vendor code; it can die mid
        // sentence, and what arrives is whatever was in the pipe.
        val result = QnnProbeResult.parse("segmentation fault")

        assertFalse(result.ok)
        assertFalse(result.htpUsable)
        assertNull(result.pid)
        assertTrue(result.summary().startsWith("QNN unavailable"))
    }

    @Test fun anUnfamiliarCapabilityNeverMeansYes() {
        // A future runtime could report something new. Absent, unknown or
        // unreadable all have to read the same way: no.
        assertFalse(QnnProbeResult.parse("""{"ok":true,"capabilities":{"npu":1}}""").htpUsable)
        assertFalse(QnnProbeResult.parse("""{"ok":true}""").htpUsable)
        assertFalse(QnnProbeResult.unavailable("process gone").htpUsable)
    }
}
