package io.github.m96chan.droidrunner.npu

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this test can and cannot reach (issues #197, #198).
 *
 * `libnnapi_probe.so` is not loadable in a JVM unit test, so `ensureLoaded`
 * returns false here and every call below takes the Kotlin fallback branch.
 * That is worth asserting — those strings are handed to the same parsers as
 * the native ones and are as easy to break — but it is not coverage of the
 * C. The overflow in #198 and the shared buffers in #197 live on the other
 * side of a `System.loadLibrary` that only an Android device crosses, and
 * the reply from a driver with a 300-character name needs a driver with a
 * 300-character name. Nothing here is stubbed to pretend otherwise.
 *
 * The last test covers the part of the fix that does reach the JVM: the
 * reply shape gained a `truncated` flag, and the consumer that reads these
 * lists has to keep working when it is there.
 */
class NnapiProbeTest {

    @Test fun devicesAlwaysParsesAsJson() {
        val reply = NnapiProbe.devices()
        val parsed = JSONObject(reply)  // throws if the probe answered with anything else
        assertTrue(parsed.has("available"))
    }

    @Test fun benchmarkAlwaysParsesAsJson() {
        val parsed = JSONObject(NnapiProbe.benchmark("qti-dsp", 10))
        assertTrue(parsed.has("ok"))
    }

    @Test fun convAlwaysParsesAsJson() {
        val parsed = JSONObject(NnapiProbe.conv("qti-dsp", 10, 32, 8, 8))
        assertTrue(parsed.has("ok"))
    }

    @Test fun anUnloadedProbeSaysSoRatherThanClaimingADevice() {
        // The library cannot load off a device, so this is the fallback text
        // by definition; it is here so a reply that says nothing is never
        // mistaken for one that says no.
        assertFalse(JSONObject(NnapiProbe.devices()).optBoolean("available"))
        assertFalse(JSONObject(NnapiProbe.benchmark(null, 1)).optBoolean("ok"))
        assertFalse(JSONObject(NnapiProbe.conv(null, 1, 8, 1, 1)).optBoolean("ok"))
    }

    /**
     * The agent runs two workers, so these entry points are called from more
     * than one thread. On the JVM this exercises the Kotlin wrapper only —
     * the load flag, the lock, and the fallback strings — and would catch a
     * caller-visible buffer being reintroduced on this side of the bridge.
     */
    @Test fun concurrentCallersEachGetTheirOwnParseableReply() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val work = (0 until THREADS).map { index ->
                Callable {
                    repeat(ROUNDS) {
                        assertTrue(JSONObject(NnapiProbe.devices()).has("available"))
                        assertTrue(JSONObject(NnapiProbe.benchmark("device-$index", 1)).has("ok"))
                        assertTrue(JSONObject(NnapiProbe.conv("device-$index", 1, 8, 1, 1)).has("ok"))
                    }
                    index
                }
            }
            val done = pool.invokeAll(work, 60, TimeUnit.SECONDS).map { it.get() }
            assertEquals((0 until THREADS).toList(), done)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun aTruncatedDeviceListIsStillReadByTheLabeller() {
        // The shape devicesJson now returns when a driver name does not fit:
        // a short list, valid JSON, and a flag saying it is short.
        val truncated = """
            {"available":true,"devices":[
              {"name":"qti-dsp","type":"accelerator","version":"1.0","featureLevel":7}],
             "truncated":true}
        """.trimIndent()
        assertTrue(NpuLabels.fromDevicesJson(truncated).containsAll(setOf("nnapi", "npu-qnn")))
    }

    private companion object {
        const val THREADS = 4
        const val ROUNDS = 50
    }
}
