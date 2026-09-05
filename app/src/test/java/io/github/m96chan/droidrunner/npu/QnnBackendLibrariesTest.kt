package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which libraries a backend needs, and what to say when one is absent (#140).
 *
 * `QnnBackend` has accepted "gpu" and "dsp" since #82 while the installer
 * fetched neither. Asking for the Adreno failed inside the delegate with
 * "Failed to apply delegate" and an empty vendor string — for a file we knew
 * perfectly well was not there.
 */
class QnnBackendLibrariesTest {

    @Test fun theAdrenoNeedsNoneOfTheHexagonsApparatus() {
        val order = QnnLibraries.loadOrder(75, "gpu")!!

        assertTrue(order.contains(QnnArtifacts.GPU_LIBRARY))
        // No prepare, no stub: those belong to the DSP path, and asking for
        // them would only fail to find files this backend never uses.
        assertTrue(order.none { it.contains("Prepare") || it.contains("Stub") })
        assertEquals(QnnLibraries.DELEGATE, order.last())
    }

    @Test fun theHexagonOrderIsUnchanged() {
        val order = QnnLibraries.loadOrder(75)!!

        assertTrue(order.contains("libQnnHtpPrepare.so"))
        assertTrue(order.contains("libQnnHtpV75Stub.so"))
        assertEquals(QnnLibraries.DELEGATE, order.last())
    }

    @Test fun aBackendWhoseLibraryIsAbsentIsNamed() {
        val missing = QnnLibraries.missingLibraryFor("gpu", setOf("libQnnHtp.so"))

        assertEquals(QnnArtifacts.GPU_LIBRARY, missing)
    }

    @Test fun aBackendWhoseLibraryIsPresentIsNotComplainedAbout() {
        assertNull(
            QnnLibraries.missingLibraryFor("gpu", setOf(QnnArtifacts.GPU_LIBRARY)),
        )
    }

    @Test fun theDspBackendIsStillNotShippedAndSaysWhichFileItWants() {
        // Accepted by QnnBackend and mapped by QnnOptions, and nothing fetches
        // it — the same gap the Adreno had until this change.
        assertEquals("libQnnDsp.so", QnnLibraries.missingLibraryFor("dsp", emptySet()))
    }

    @Test fun theHexagonIsNeverReportedAsMissingSomething() {
        assertNull(QnnLibraries.missingLibraryFor("htp", emptySet()))
    }

    @Test fun anInstallIsIdentifiedByWhatIsInItAndNotOnlyByItsVersion() {
        // The stamp did not name the libraries, so adding one would have left
        // every existing device believing it was current and never fetching it.
        val stamp = QnnArtifacts.stamp(75)

        assertTrue(stamp.startsWith(QnnArtifacts.VERSION))
        assertNotEquals(QnnArtifacts.stamp(75), QnnArtifacts.stamp(73))
    }

    @Test fun theAdrenoIsPartOfWhatEveryQualcommDeviceInstalls() {
        val libraries = QnnArtifacts.entriesFor(75)!!.map { it.library }

        assertTrue(QnnArtifacts.GPU_LIBRARY in libraries)
    }
}
