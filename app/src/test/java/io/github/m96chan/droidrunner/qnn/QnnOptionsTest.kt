package io.github.m96chan.droidrunner.qnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QnnOptionsTest {

    @Test fun theBackendCodesAreTheOnesTheHeaderNumbers() {
        // Written into the first field of the delegate's own default options.
        // Getting this wrong once meant kUndefinedBackend, and the delegate
        // walked into a backend that does not exist and took the process down.
        assertEquals(2, QnnOptions.backendCode("htp"))
        assertEquals(1, QnnOptions.backendCode("gpu"))
        assertEquals(2, QnnOptions.backendCode("HTP"))
    }

    @Test fun somethingThatIsNotAQnnBackendHasNoCode() {
        assertNull(QnnOptions.backendCode("edgetpu"))
        assertNull(QnnOptions.backendCode(""))
    }
}
