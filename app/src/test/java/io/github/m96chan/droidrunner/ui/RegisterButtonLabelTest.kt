package io.github.m96chan.droidrunner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.github.m96chan.droidrunner.model.RegistrationCredentialSource
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Test

/**
 * What the register button says when it cannot be pressed (issue #150).
 *
 * The reason had been under the button all along, dim and small, and it did
 * not survive contact with a user trying to switch repositories — the report
 * behind this was "the Re-register button does not become active", written by
 * someone looking straight at the control and not at the note beneath it.
 */
class RegisterButtonLabelTest {

    @Test fun aStoppedPatRunnerCanSwitchToSignInOnTheSameTarget() {
        val target = RunnerTarget.Repository("owner", "repo")
        val stored = RunnerConfig(target, "android", emptySet(), RegistrationCredentialSource.PAT)
        assertTrue(canRegisterWithSignIn(stored, target, false, true, true))
        assertEquals(
            "Re-register owner/repo with GitHub sign-in",
            registerButtonLabel(target.displayName, registeredWithSignIn(stored, target), false, true, true),
        )
    }

    @Test fun switchingCredentialsRequiresTheRunnerToBeStopped() {
        val target = RunnerTarget.Repository("owner", "repo")
        val stored = RunnerConfig(target, "android", emptySet(), RegistrationCredentialSource.PAT)
        assertFalse(canRegisterWithSignIn(stored, target, false, true, false))
        assertEquals(
            "Stop the runner to re-register",
            registerButtonLabel(target.displayName, registeredWithSignIn(stored, target), false, false, true),
        )
    }

    @Test fun theSameSignInRegistrationNeedsNoChange() {
        val target = RunnerTarget.Repository("owner", "repo")
        val stored = RunnerConfig(target, "android", emptySet(), RegistrationCredentialSource.SIGN_IN)
        assertTrue(registeredWithSignIn(stored, target))
        assertFalse(canRegisterWithSignIn(stored, target, false, true, true))
    }

    @Test fun aLegacyRegistrationCanExplicitlySelectSignIn() {
        val target = RunnerTarget.Repository("owner", "repo")
        val stored = RunnerConfig(target, "android", emptySet())
        assertTrue(canRegisterWithSignIn(stored, target, false, true, true))
    }

    @Test fun aRunningRunnerIsToldToStopBeforeSwitching() {
        assertEquals(
            "Stop the runner to re-register",
            registerButtonLabel(
                target = "m96-chan/NxPU",
                alreadyRegistered = false,
                firstRegistration = false,
                runnerStopped = false,
            ),
        )
    }

    @Test fun aStoppedRunnerIsOfferedTheSwitchItself() {
        assertEquals(
            "Re-register as m96-chan/NxPU",
            registerButtonLabel(
                target = "m96-chan/NxPU",
                alreadyRegistered = false,
                firstRegistration = false,
                runnerStopped = true,
            ),
        )
    }

    @Test fun aFirstRegistrationDoesNotAskForAStopThereIsNothingToStop() {
        // Nothing is registered, so nothing is listening, and telling someone
        // to stop a runner they have never started is worse than saying nothing.
        assertEquals(
            "Register m96-chan/DroidRunner",
            registerButtonLabel(
                target = "m96-chan/DroidRunner",
                alreadyRegistered = false,
                firstRegistration = true,
                runnerStopped = false,
            ),
        )
    }

    @Test fun theStoredTargetIsNamedRatherThanOfferedAgain() {
        assertEquals(
            "Registered: m96-chan/DroidRunner",
            registerButtonLabel(
                target = "m96-chan/DroidRunner",
                alreadyRegistered = true,
                firstRegistration = false,
                runnerStopped = true,
            ),
        )
    }

    @Test fun aRunningRunnerOnItsOwnTargetStillJustSaysRegistered() {
        // The common case: the screen is open on the device that is working.
        // "Stop the runner" would be an instruction to undo that for no reason.
        assertEquals(
            "Registered: m96-chan/DroidRunner",
            registerButtonLabel(
                target = "m96-chan/DroidRunner",
                alreadyRegistered = true,
                firstRegistration = false,
                runnerStopped = false,
            ),
        )
    }
}
