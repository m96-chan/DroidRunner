package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.device.HexagonVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnArtifactsTest {

    @Test fun theFleetsPhonesResolveFromTheirSocStringToAFetchList() {
        // The whole path stage 1 and stage 2 form: what the device reports,
        // through the Hexagon generation, to the files to fetch.
        val version = HexagonVersion.of("qti sm8650 qcom pineapple")
        assertNotNull(version)
        val entries = QnnArtifacts.entriesFor(version!!)!!

        assertTrue(entries.map { it.library }.containsAll(HexagonVersion.libraries(75)))
    }

    @Test fun noOtherHexagonGenerationIsFetched() {
        // Fetching all six generations would cost 64MB instead of 38MB, and
        // five of them could never run on the device that downloaded them.
        val libraries = QnnArtifacts.entriesFor(75)!!.map { it.library }

        val strays = libraries.filter { it.contains("HtpV") && !it.contains("HtpV75") }
        assertEquals(emptyList<String>(), strays)
    }

    @Test fun everyGenerationNeedsThePreparerAndTheSystemLibrary() {
        // These are version-independent and easy to forget: without
        // libQnnHtpPrepare.so nothing can compile a graph for the NPU, and the
        // failure surfaces far from the omission.
        for (version in listOf(68, 69, 73, 75, 79, 81)) {
            val libraries = QnnArtifacts.entriesFor(version)!!.map { it.library }
            assertTrue(
                "v$version is missing a shared library: $libraries",
                libraries.containsAll(
                    listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so"),
                ),
            )
        }
    }

    @Test fun aGenerationQualcommDoesNotPublishIsRefusedRatherThanPartlyFetched() {
        assertNull(QnnArtifacts.entriesFor(1))
        assertEquals(0L, QnnArtifacts.installBytes(1))
    }

    @Test fun everyPinIsAFullDigestAndBelongsToOneFileOnly() {
        // A copy-paste slip between two rows of the table would pin a library
        // to another library's digest, which fails only once a device tries it.
        val entries = listOf(68, 69, 73, 75, 79, 81).flatMap { QnnArtifacts.entriesFor(it)!! }
            .distinctBy { it.library }

        entries.forEach { entry ->
            assertTrue(entry.library, entry.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(entry.library, entry.bytes > 0)
        }
        assertEquals(entries.size, entries.map { it.sha256 }.distinct().size)
    }

    @Test fun eachEntryIsAddressedInsideTheAarThatShipsIt() {
        val delegate = QnnArtifacts.entriesFor(75)!!.first { it.library == "libqnn_delegate_jni.so" }

        assertEquals("jni/arm64-v8a/libqnn_delegate_jni.so", delegate.zipEntry)
        assertEquals(
            "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-litert-delegate/" +
                "${QnnArtifacts.VERSION}/qnn-litert-delegate-${QnnArtifacts.VERSION}.aar",
            QnnArtifacts.url(delegate.module),
        )
    }

    @Test fun theStampNamesBothTheReleaseAndTheGeneration() {
        // An install is skipped when the stamp matches, so it has to change
        // when either the QNN release or the device's generation does.
        assertEquals("${QnnArtifacts.VERSION} v75", QnnArtifacts.stamp(75))
        assertTrue(QnnArtifacts.stamp(75) != QnnArtifacts.stamp(73))
    }
}
