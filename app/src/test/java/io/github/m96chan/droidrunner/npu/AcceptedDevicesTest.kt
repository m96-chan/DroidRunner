package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of values `--device` accepts (issue #158).
 *
 * Filed by a consumer who swept the GPU only because the README mentions it in
 * prose. `devices` reports NNAPI drivers, and on the phone in question the GPU
 * — which is not one — accepted more operators than the NPU did. Enumerating
 * accelerators from `devices` silently omitted the best one.
 */
class AcceptedDevicesTest {

    private val oneDriver = """{"available":true,"devices":[{"name":"nnapi-reference"}]}"""

    @Test fun everyNnapiDriverIsAccepted() {
        val accepts = DeviceCapabilitiesJson.accepts(
            """{"devices":[{"name":"mtk-mdla_shim"},{"name":"nnapi-reference"}]}""",
            qnnInstalled = false,
        )

        assertTrue(accepts.containsAll(listOf("mtk-mdla_shim", "nnapi-reference")))
    }

    @Test fun theGpuIsAlwaysThereBecauseTheDelegateShipsInTheApk() {
        // Deliberately not conditional on the compatibility list: it answers
        // false on an SM8650 whose Adreno runs graphs perfectly well, and
        // gating on it is exactly the mistake this list exists to prevent.
        assertTrue(DeviceCapabilitiesJson.accepts(oneDriver, qnnInstalled = false).contains("gpu"))
    }

    @Test fun qnnAppearsOnlyOnceItsRuntimeIsOnTheDevice() {
        val without = DeviceCapabilitiesJson.accepts(oneDriver, qnnInstalled = false)
        val with = DeviceCapabilitiesJson.accepts(oneDriver, qnnInstalled = true)

        assertFalse(without.any { it.startsWith("qnn-") })
        // Offering a name that answers `not-installed` would be worse than
        // omitting it: the point of the list is that asking for what it names
        // works.
        assertEquals(listOf("qnn-gpu", "qnn-htp"), with.filter { it.startsWith("qnn-") })
    }

    @Test fun theQnnNamesComeFromTheObjectThatAcceptsThem() {
        // Not a second copy of the list. A copy is what eventually disagrees
        // with the one that decides.
        assertEquals(QnnBackend.names(), listOf("qnn-gpu", "qnn-htp"))
        assertEquals("gpu", QnnBackend.of("qnn-gpu"))
        assertEquals("htp", QnnBackend.of("qnn-htp"))
    }

    @Test fun anNnapiPayloadThatCannotBeReadStillLeavesTheGpuOffered() {
        // The probe answering oddly must not take the rest of the list with
        // it: `gpu` is true regardless of what NNAPI says about itself.
        assertEquals(listOf("gpu"), DeviceCapabilitiesJson.accepts("not json", qnnInstalled = false))
        assertEquals(listOf("gpu"), DeviceCapabilitiesJson.accepts("{}", qnnInstalled = false))
    }
}
