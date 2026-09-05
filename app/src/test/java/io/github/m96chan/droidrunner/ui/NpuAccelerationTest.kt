package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.npu.QnnArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuAccelerationTest {

    private val snapdragon = "qti sm8650 qcom pineapple"

    @Test fun aPhoneWithNoHexagonIsNotAskedAboutOne() {
        // The Tensor and MediaTek devices already reach their accelerators
        // through NNAPI. A panel about Qualcomm terms would be noise, and a
        // question nobody should answer.
        assertEquals(NpuAcceleration.Irrelevant, npuAcceleration("google tensor g4", false, null))
        assertEquals(NpuAcceleration.Irrelevant, npuAcceleration("mediatek mt6899", false, null))
    }

    @Test fun aSnapdragonWithNoMappingIsToldSoRatherThanOfferedAButton() {
        val state = npuAcceleration("qti sm9999 qcom", false, null)

        assertTrue(state is NpuAcceleration.Unsupported)
        assertTrue((state as NpuAcceleration.Unsupported).reason.isNotBlank())
    }

    @Test fun theTermsComeBeforeTheDownload() {
        // The whole point of the stage: a device that has not accepted is
        // offered the licences, never the install.
        val state = npuAcceleration(snapdragon, consentGranted = false, installed = null)

        assertEquals(
            NpuAcceleration.NeedsAcceptance(
                htpVersion = 75,
                downloadBytes = QnnArtifacts.downloadBytes(75),
                installBytes = QnnArtifacts.installBytes(75),
            ),
            state,
        )
    }

    @Test fun acceptingIsWhatTurnsTheInstallOn() {
        val state = npuAcceleration(snapdragon, consentGranted = true, installed = null)

        assertTrue(state is NpuAcceleration.Installable)
        assertEquals(75, (state as NpuAcceleration.Installable).htpVersion)
    }

    @Test fun aRuntimeInstalledForSomethingElseIsNotThisDevicesRuntime() {
        // A stamp from another Hexagon generation, or from an older QNN
        // release, must read as "not installed here" — the libraries it holds
        // cannot run on this phone.
        assertTrue(
            npuAcceleration(snapdragon, true, QnnArtifacts.stamp(73)) is NpuAcceleration.Installable,
        )
        assertTrue(
            npuAcceleration(snapdragon, true, "2.30.0 v75") is NpuAcceleration.Installable,
        )
        assertEquals(
            NpuAcceleration.Installed(QnnArtifacts.stamp(75), 75),
            npuAcceleration(snapdragon, true, QnnArtifacts.stamp(75)),
        )
    }

    @Test fun bothCostsAreQuotedBecauseEitherAloneMisleads() {
        // 41MB crosses the network and 110MB stays on the phone; a user told
        // only the first would be surprised by the second. Both grew when the
        // Adreno backend joined the install (#140).
        assertEquals(
            "41 MB to download, 110 MB on disk",
            downloadSummary(QnnArtifacts.downloadBytes(75), QnnArtifacts.installBytes(75)),
        )
    }
}
