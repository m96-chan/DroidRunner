package io.github.m96chan.droidrunner.runner

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifetimeTest {

    @Test fun theCurrentSupervisorTakesTheServiceWithItWhenItFinishes() {
        // Nothing else is running, so leaving the service up would leave a
        // foreground notification with no runner behind it.
        assertTrue(ServiceLifetime.shouldStopService(1, currentGeneration = 1, stopRequested = false))
    }

    @Test fun aSupersededSupervisorLeavesTheServiceAlone() {
        // This is issue #68: Stop then Start leaves the old supervisor winding
        // down while a new start already owns the service. If the old one
        // stops it, the device shows Stopped — indistinguishable from someone
        // stopping it deliberately — and nothing is running.
        assertFalse(ServiceLifetime.shouldStopService(1, currentGeneration = 2, stopRequested = false))
    }

    @Test fun anExplicitStopIsNotUndoneByTheSupervisorFinishing() {
        // stopRunner already tore everything down; calling stopSelf again is
        // harmless but the intent matters — this path must not be the one that
        // decides.
        assertFalse(ServiceLifetime.shouldStopService(1, currentGeneration = 1, stopRequested = true))
    }

    @Test fun aSupervisorStopsLoopingWhenANewerStartArrives() {
        // The supervisor runs on a single-thread executor: an old one that
        // keeps looping holds the queue, and the start that replaced it never
        // begins at all.
        assertFalse(ServiceLifetime.shouldKeepRunning(1, currentGeneration = 2, stopRequested = false))
    }

    @Test fun aSupervisorKeepsLoopingWhileItIsStillTheCurrentOne() {
        assertTrue(ServiceLifetime.shouldKeepRunning(3, currentGeneration = 3, stopRequested = false))
        assertFalse(ServiceLifetime.shouldKeepRunning(3, currentGeneration = 3, stopRequested = true))
    }

    // --- the stop that used to freeze the app (issue #209) ------------------

    private fun onSomeThread(): (Runnable) -> Unit = { body -> thread(name = "halt") { body.run() } }

    // Bounded, because the failure this guards against is a halt that never
    // gives the caller back: without a timeout that reads as a hung build.
    @Test(timeout = 30_000) fun aStopDoesNoWaitingOnTheThreadThatAskedForIt() {
        // ACTION_STOP is delivered with startService, so this is the main
        // thread, and the halt behind it waits out a SIGINT shutdown this
        // project measured at about nineteen seconds. Waiting here is an
        // input-dispatch ANR at five seconds and a frozen dashboard for the
        // rest — which is what gets Stop tapped twice and then force-stopped.
        val halting = AtomicBoolean(false)
        val caller = Thread.currentThread()
        val ranOn = AtomicReference<Thread?>(null)
        val finished = CountDownLatch(1)

        val elapsed = measureTimeMillis {
            assertTrue(
                ServiceLifetime.beginStop(halting, onSomeThread()) {
                    ranOn.set(Thread.currentThread())
                    // Stands in for the twenty seconds of signalling and
                    // polling that the real halt spends.
                    Thread.sleep(500)
                    finished.countDown()
                },
            )
        }

        assertTrue("the caller waited ${elapsed}ms for the halt", elapsed < 250)
        assertTrue("the halt never ran", finished.await(10, TimeUnit.SECONDS))
        assertNotSame(caller, ranOn.get())
    }

    // Bounded, because the failure this guards against is a halt that never
    // gives the caller back: without a timeout that reads as a hung build.
    @Test(timeout = 30_000) fun tappingStopAgainJoinsTheHaltAlreadyRunning() {
        // Two halts signalling the same proot tree, each waiting on the other's
        // pids, is not a second chance at stopping — it is two stops racing to
        // take the service down under one another.
        val halting = AtomicBoolean(false)
        val halts = AtomicInteger(0)
        val firstIsIn = CountDownLatch(1)
        val letItFinish = CountDownLatch(1)
        val done = CountDownLatch(1)

        assertTrue(
            ServiceLifetime.beginStop(halting, onSomeThread()) {
                halts.incrementAndGet()
                firstIsIn.countDown()
                letItFinish.await()
                done.countDown()
            },
        )
        assertTrue(firstIsIn.await(10, TimeUnit.SECONDS))

        assertFalse(ServiceLifetime.beginStop(halting, onSomeThread()) { halts.incrementAndGet() })

        letItFinish.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, halts.get())
    }

    // Bounded, because the failure this guards against is a halt that never
    // gives the caller back: without a timeout that reads as a hung build.
    @Test(timeout = 30_000) fun theNextStopIsNotRefusedBecauseAnEarlierOneFinished() {
        // The service is started and stopped all day by admission control and
        // by the watchdog; a halt that forgot to give the flag back would make
        // the second stop of a device's life a no-op.
        val halting = AtomicBoolean(false)
        val first = CountDownLatch(1)
        ServiceLifetime.beginStop(halting, onSomeThread()) { first.countDown() }
        assertTrue(first.await(10, TimeUnit.SECONDS))

        val second = CountDownLatch(1)
        val deadline = System.currentTimeMillis() + 10_000
        while (halting.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(ServiceLifetime.beginStop(halting, onSomeThread()) { second.countDown() })
        assertTrue(second.await(10, TimeUnit.SECONDS))
    }

    // Bounded, because the failure this guards against is a halt that never
    // gives the caller back: without a timeout that reads as a hung build.
    @Test(timeout = 30_000) fun aHaltThatThrowsStillGivesTheFlagBack() {
        val halting = AtomicBoolean(false)
        val thrown = CountDownLatch(1)
        ServiceLifetime.beginStop(halting, { body -> thread { runCatching { body.run() } } }) {
            thrown.countDown()
            throw IllegalStateException("the /proc scan failed")
        }
        assertTrue(thrown.await(10, TimeUnit.SECONDS))

        val deadline = System.currentTimeMillis() + 10_000
        while (halting.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("a failed halt left the service unable to stop", halting.get())
    }

    // --- one bad pass is not the end of the runner (issue #211) -------------

    @Test fun aTransientErrorInOnePassIsSurvived() {
        // Every one of these was reachable from the supervisor loop and ended
        // it for good: one StatFs on a path that had gone away, one receiver
        // the system would not register, one fork refused under memory
        // pressure while a job was building.
        assertTrue(ServiceLifetime.survivesIterationError(IllegalStateException("StatFs failed")))
        assertTrue(ServiceLifetime.survivesIterationError(IOException("Cannot run program: out of memory")))
        assertTrue(ServiceLifetime.survivesIterationError(NullPointerException()))
        assertTrue(ServiceLifetime.survivesIterationError(SecurityException("registerReceiver denied")))
    }

    @Test fun anInterruptIsTheServiceItselfLeaving() {
        // The only interrupt the supervisor ever gets is executor.shutdownNow()
        // from onDestroy, so this one really does mean stop.
        assertFalse(ServiceLifetime.survivesIterationError(InterruptedException()))
    }

    @Test fun aFaultThatNeverClearsIsSaidOnceAndThenRarely() {
        // The log is a hundred lines long and is what a bug report carries; a
        // line every five seconds for a permanent fault erases everything that
        // led up to it — the mistake #214 is about, made at twelve times the
        // rate.
        assertTrue(ServiceLifetime.shouldReportIterationError(null, "StatFs failed", repeats = 0))
        assertFalse(ServiceLifetime.shouldReportIterationError("StatFs failed", "StatFs failed", repeats = 1))
        assertFalse(
            ServiceLifetime.shouldReportIterationError(
                "StatFs failed",
                "StatFs failed",
                repeats = ServiceLifetime.QUIET_REPEATS - 1,
            ),
        )
        assertTrue(
            ServiceLifetime.shouldReportIterationError(
                "StatFs failed",
                "StatFs failed",
                repeats = ServiceLifetime.QUIET_REPEATS,
            ),
        )
    }

    @Test fun aDifferentFaultIsNewsEvenInTheMiddleOfAStreak() {
        assertTrue(ServiceLifetime.shouldReportIterationError("StatFs failed", "no such device", repeats = 7))
    }
}
