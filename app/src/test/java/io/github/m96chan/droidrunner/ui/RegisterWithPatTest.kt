package io.github.m96chan.droidrunner.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the advanced panel's **Register with PAT** button can be pressed
 * (issue #194).
 *
 * It had no runner-state term at all, so it stayed live while a listener was
 * running and took `clearLocalRegistration` and `config.sh` to the identity
 * files that listener was holding open — the failure #154 and #150 are about,
 * reachable from the one button that never checked.
 */
class RegisterWithPatTest {

    private fun canRegister(
        busy: Boolean = false,
        owner: String = "m96-chan",
        repository: String = "DroidRunner",
        pat: String = "ghp_typed_by_hand",
        runtimeAvailable: Boolean = true,
        runnerStopped: Boolean = true,
    ) = canRegisterWithPat(busy, owner, repository, pat, runtimeAvailable, runnerStopped)

    @Test fun aRunningRunnerIsNotRegisteredOverTheTopOf() {
        assertFalse(canRegister(runnerStopped = false))
    }

    @Test fun aStoppedRunnerWithEveryFieldFilledInCanRegister() {
        assertTrue(canRegister())
    }

    @Test fun everyFieldIsStillRequired() {
        assertFalse(canRegister(owner = ""))
        assertFalse(canRegister(repository = ""))
        assertFalse(canRegister(pat = ""))
    }

    @Test fun thereIsNothingToRegisterWithoutARuntimeToRegisterWith() {
        assertFalse(canRegister(runtimeAvailable = false))
    }

    @Test fun aSetupAlreadyUnderWayIsNotInterrupted() {
        assertFalse(canRegister(busy = true))
    }
}
