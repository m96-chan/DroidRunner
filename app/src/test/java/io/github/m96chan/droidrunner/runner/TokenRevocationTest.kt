package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #172: a token that outlived the listener holding it. */
class TokenRevocationTest {

    // Two distinct references. Nothing is executed; identity is the whole rule.
    private val first = ProcessBuilder("true").let { object : Process() {
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    } }
    private val second = object : Process() {
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }

    @Test fun theListenerThatJustEndedRevokesItsOwnToken() {
        assertTrue(TokenRevocation.shouldRevoke(current = first, ended = first))
    }

    @Test fun anOlderListenerFinishingLateLeavesTheNewJobAlone() {
        // The supervisor restarts fast enough that a replacement can already be
        // running a job with a fresh token. Revoking here would cut off the job
        // that is actually running, which is worse than the bug being fixed.
        assertFalse(TokenRevocation.shouldRevoke(current = second, ended = first))
    }

    @Test fun nothingRunningMeansThereIsNothingToProtect() {
        assertTrue(TokenRevocation.shouldRevoke(current = null, ended = first))
    }
}
