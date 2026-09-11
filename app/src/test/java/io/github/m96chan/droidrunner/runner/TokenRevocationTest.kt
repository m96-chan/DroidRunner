package io.github.m96chan.droidrunner.runner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #172: a token that outlived the listener holding it. */
class TokenRevocationTest {

    /** Two distinct references. Nothing is executed; identity is the whole rule. */
    private fun handle() = object : Process() {
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }

    private val first = handle()
    private val second = handle()

    @Test fun theListenerThatJustEndedRevokesItsOwnToken() {
        val current = AtomicReference<Process?>(first)

        assertTrue(TokenRevocation.revokeIfCurrent(current, first))
        assertNull(current.get())
    }

    @Test fun anOlderListenerFinishingLateLeavesTheNewJobAlone() {
        // The supervisor restarts fast enough that a replacement can already be
        // running a job with a fresh token. Revoking here would cut off the job
        // that is actually running, which is worse than the bug being fixed.
        val current = AtomicReference<Process?>(second)

        assertFalse(TokenRevocation.revokeIfCurrent(current, first))
        // And — issue #212 — it must not clear the handle either. The service
        // reads this to decide whether a listener exists; nulling it here made
        // the next poll sweep the strays and SIGKILL the proot tree of the job
        // that was running.
        assertSame(second, current.get())
    }

    @Test fun aHandleAlreadyClearedIsNobodysToRevoke() {
        // stopListener() takes the handle and revokes on its own account, so
        // there is nothing owed by the thread that arrives afterwards.
        val current = AtomicReference<Process?>(null)

        assertFalse(TokenRevocation.revokeIfCurrent(current, first))
    }

    @Test(timeout = 120_000) fun aReplacementStartingMidGuardKeepsItsHandleAndItsToken() {
        // The interleaving from #212, run for real: the old thread reaches the
        // guard at the moment the supervisor swaps in a replacement. With the
        // test and the clear as two steps, the old thread's null lands on top
        // of the live listener — the supervisor then sees no process, sweeps
        // the strays, and kills a running job.
        repeat(10_000) {
            val current = AtomicReference<Process?>(first)
            val bothReady = CountDownLatch(2)

            val older = thread {
                bothReady.countDown()
                bothReady.await()
                TokenRevocation.revokeIfCurrent(current, first)
            }
            val supervisor = thread {
                bothReady.countDown()
                bothReady.await()
                // stopListener() clears it; startListener() installs the new one.
                current.set(null)
                current.set(second)
            }
            older.join(10_000)
            supervisor.join(10_000)

            // Whoever won the race, the handle the service is left holding is
            // the live listener's.
            assertSame("the replacement's handle was lost on run $it", second, current.get())
        }
    }

    @Test fun aLateThreadNeverRevokesWhileTheReplacementIsAlreadyCurrent() {
        // The same race the other way round, held still: by the time the old
        // thread asks, the replacement is installed and has a token of its own.
        val current = AtomicReference<Process?>(first)
        val installed = CountDownLatch(1)

        thread {
            current.set(null)
            current.set(second)
            installed.countDown()
        }
        assertTrue(installed.await(10, TimeUnit.SECONDS))

        assertFalse(TokenRevocation.revokeIfCurrent(current, first))
        assertSame(second, current.get())
    }
}
