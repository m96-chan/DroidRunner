package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertFalse
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
}
