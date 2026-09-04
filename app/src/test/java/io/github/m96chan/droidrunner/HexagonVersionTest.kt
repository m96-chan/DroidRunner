package io.github.m96chan.droidrunner.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HexagonVersionTest {

    @Test fun theFleetsSnapdragonsAreRecognised() {
        // Both strings are what the devices actually report, taken from their
        // runner labels: soc-qti-sm8650-qcom and soc-qti-sm8550-qcom.
        assertEquals(75, HexagonVersion.of("qti sm8650 qcom pineapple"))
        assertEquals(73, HexagonVersion.of("qti sm8550 qcom kalama"))
    }

    @Test fun aNonQualcommDeviceHasNoHexagonAtAll() {
        // Asking a MediaTek or a Tensor for an HTP version is a category error;
        // it must not fall through to a substring match on a model number.
        assertNull(HexagonVersion.of("mediatek mt6899"))
        assertNull(HexagonVersion.of("google tensor g4 stallion"))
    }

    @Test fun anUnknownSnapdragonIsAdmittedRatherThanGuessed() {
        // Fetching megabytes of the wrong runtime and failing during inference
        // is worse than a sentence saying this device is not supported yet.
        val soc = "qti sm9999 qcom"
        assertNull(HexagonVersion.of(soc))
        assertNotNull(HexagonVersion.unsupportedReason(soc))
    }

    @Test fun nothingIsSaidAboutDevicesThisDoesNotConcern() {
        assertNull(HexagonVersion.unsupportedReason("mediatek mt6899"))
        assertNull(HexagonVersion.unsupportedReason("qti sm8650 qcom"))
    }

    @Test fun theLibraryNameFollowsTheVersion() {
        // The name is how the fetch stage will address the artifact, so the
        // shape matters as much as the number.
        assertEquals("libQnnHtpV75.so", HexagonVersion.runtimeLibrary(75))
        assertEquals("libQnnHtpV73.so", HexagonVersion.runtimeLibrary(73))
    }
}
