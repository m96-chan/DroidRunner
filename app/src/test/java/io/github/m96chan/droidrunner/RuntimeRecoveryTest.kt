package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.runner.RunnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryTest {

    @Test fun aRegisteredDeviceThatLostItsRuntimeIsOfferedAnotherOne() {
        // The state issue #46 describes: registration intact, runtime gone.
        assertTrue(RuntimeRecovery.shouldOfferInstall(runtimeInstalled = false, manifestAvailable = true))
    }

    @Test fun aDeviceWithARuntimeIsNotAskedToInstallOne() {
        assertFalse(RuntimeRecovery.shouldOfferInstall(runtimeInstalled = true, manifestAvailable = true))
    }

    @Test fun nothingIsOfferedWhenThereIsNothingToInstall() {
        // No release found and no manifest override: the button would only
        // fail, and the panel already says to set a URL under advanced.
        assertFalse(RuntimeRecovery.shouldOfferInstall(runtimeInstalled = false, manifestAvailable = false))
    }

    @Test fun installingWaitsForTheListenerToBeDown() {
        // It replaces the directory the listener runs from.
        assertFalse(RuntimeRecovery.canInstallNow(busy = false, RunnerState.LISTENING))
        assertFalse(RuntimeRecovery.canInstallNow(busy = false, RunnerState.JOB_RUNNING))
        assertFalse(RuntimeRecovery.canInstallNow(busy = false, RunnerState.PAUSED))
        assertTrue(RuntimeRecovery.canInstallNow(busy = false, RunnerState.STOPPED))
    }

    @Test fun installingWaitsForWhateverSetupIsAlreadyDoing() {
        assertFalse(RuntimeRecovery.canInstallNow(busy = true, RunnerState.STOPPED))
    }

    @Test fun aRepairedRunnerComesBackUpOnItsOwn() {
        assertTrue(RuntimeRecovery.shouldStartRunnerAfterInstall(registered = true, RunnerState.STOPPED))
    }

    @Test fun aDeviceThatNeverRegisteredHasNothingToStart() {
        assertFalse(RuntimeRecovery.shouldStartRunnerAfterInstall(registered = false, RunnerState.STOPPED))
    }

    @Test fun aRunnerThatIsSomehowAlreadyUpIsNotStartedTwice() {
        assertFalse(RuntimeRecovery.shouldStartRunnerAfterInstall(registered = true, RunnerState.LISTENING))
    }

    @Test fun theButtonSaysWhetherThisIsARepairOrAFirstInstall() {
        assertEquals("Reinstall runtime", RuntimeRecovery.installLabel(registered = true))
        assertEquals("Install runtime", RuntimeRecovery.installLabel(registered = false))
    }
}
