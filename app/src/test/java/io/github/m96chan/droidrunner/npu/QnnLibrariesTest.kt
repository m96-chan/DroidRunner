package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnLibrariesTest {

    @Test fun theDelegateIsOpenedLast() {
        // It is the library the probe then asks questions of, and the QNN
        // runtime reaches for its own pieces by bare soname — which only
        // resolves for something already in the namespace, since the linker
        // will not search app data.
        assertEquals(QnnLibraries.DELEGATE, QnnLibraries.loadOrder(75)!!.last())
    }

    @Test fun onlyThisDevicesStubIsOpened() {
        // The stub is the host half of a generation-specific pair. Opening
        // another generation's would be loading code for silicon that is not
        // in this phone.
        val order = QnnLibraries.loadOrder(75)!!

        assertTrue(order.contains("libQnnHtpV75Stub.so"))
        assertEquals(emptyList<String>(), order.filter { it.contains("V73") || it.contains("V79") })
    }

    @Test fun theSkelIsNeverOpenedHere() {
        // It runs on the DSP and is loaded by the DSP loader through
        // ADSP_LIBRARY_PATH. dlopen'ing it from an aarch64 process fails, and
        // the failure says nothing useful about why.
        assertFalse(QnnLibraries.loadOrder(75)!!.any { it.contains("Skel") })
    }

    @Test fun everyPublishedGenerationHasAnOrderAndAnUnpublishedOneHasNone() {
        listOf(68, 69, 73, 75, 79, 81).forEach { version ->
            val order = QnnLibraries.loadOrder(version)
            assertEquals("v$version", 5, order?.size)
            assertTrue("v$version", order!!.contains("libQnnHtpV${version}Stub.so"))
        }
        assertNull(QnnLibraries.loadOrder(1))
    }

    @Test fun thePreparerAndTheSystemLibraryAreOpenedBeforeTheBackend() {
        // libQnnHtp.so opens libQnnHtpPrepare by name while it initialises;
        // loading it afterwards would be too late.
        val order = QnnLibraries.loadOrder(75)!!

        assertTrue(order.indexOf("libQnnHtpPrepare.so") < order.indexOf("libQnnHtp.so"))
        assertTrue(order.indexOf("libQnnSystem.so") < order.indexOf("libQnnHtp.so"))
    }
}
