package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming more than one accelerator (issue #159).
 *
 * The point of the feature gate is that everything not asking for it behaves
 * exactly as it did, so most of these are about what stays the same.
 */
class DeviceRequestTest {

    private val on = setOf(DeviceRequest.FEATURE)

    @Test fun onePlainNameIsWhatItAlwaysWas() {
        assertEquals(
            DeviceRequest.Parsed.Single("mtk-neuron_shim"),
            DeviceRequest.parse("mtk-neuron_shim", emptySet()),
        )
        assertEquals(DeviceRequest.Parsed.Single(null), DeviceRequest.parse(null, emptySet()))
        assertEquals(DeviceRequest.Parsed.Single(null), DeviceRequest.parse("  ", on))
    }

    @Test fun theListFormHasToBeAskedFor() {
        // Not an error about syntax: without the feature this simply is not a
        // device name, and `unknown-device` is the code a caller already
        // branches on for that.
        val refused = DeviceRequest.parse("mtk-neuron_shim+gpu", emptySet())
                as DeviceRequest.Parsed.Refused

        assertEquals(ResultContract.Code.UNKNOWN_DEVICE, refused.code)
        assertTrue(refused.reason.contains("--feature ${DeviceRequest.FEATURE}"))
    }

    @Test fun withTheFeatureTheOrderIsKept() {
        // TFLite offers each delegate what the previous one did not claim, so
        // sorting these would change the answer.
        assertEquals(
            DeviceRequest.Parsed.Several(listOf("mtk-neuron_shim", "gpu")),
            DeviceRequest.parse("mtk-neuron_shim+gpu", on),
        )
        assertEquals(
            DeviceRequest.Parsed.Several(listOf("gpu", "mtk-neuron_shim")),
            DeviceRequest.parse("gpu+mtk-neuron_shim", on),
        )
    }

    @Test fun qualcommCannotShareAnInterpreterAndIsToldSo() {
        // Not a gap to fill later. QNN runs in another process because the
        // FSF's line for "one program" is the shared address space, and two
        // delegates need one (#82). The message says which, so a caller does
        // not read it as a missing feature.
        for (text in listOf("qnn-htp+gpu", "gpu+qnn-htp", "qnn-gpu+mtk-neuron_shim")) {
            val refused = DeviceRequest.parse(text, on) as DeviceRequest.Parsed.Refused
            assertEquals(ResultContract.Code.UNKNOWN_DEVICE, refused.code)
            assertTrue(text, refused.reason.contains("separate process"))
        }
    }

    @Test fun anEmptyNameBetweenSeparatorsIsTheCallersMistake() {
        val refused = DeviceRequest.parse("gpu+", on) as DeviceRequest.Parsed.Refused

        assertEquals(ResultContract.Code.INVALID_REQUEST, refused.code)
    }

    @Test fun theSameAcceleratorTwiceWouldLookLikeAPartitionAndIsNotOne() {
        // The second attach claims nothing the first did not, so it is a
        // silent no-op that returns a shape suggesting two partitions.
        val refused = DeviceRequest.parse("gpu+gpu", on) as DeviceRequest.Parsed.Refused

        assertEquals(ResultContract.Code.INVALID_REQUEST, refused.code)
    }

    @Test fun theSeparatorIsWhatTheProbeEndpointsLookForToRefuseEarly() {
        // `test add` and `test conv` call NNAPI directly, so there is no
        // interpreter to attach a second delegate to. The server checks for the
        // separator rather than parsing, because the reason it refuses is a
        // property of the endpoint and not of the names.
        assertTrue("gpu+nnapi-reference".contains(DeviceRequest.SEPARATOR))
        assertTrue(!"gpu".contains(DeviceRequest.SEPARATOR))
    }
}
