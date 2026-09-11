package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunnerRegistrationTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun installingARuntimeKeepsWhatTheDeviceRegisteredAs() {
        // Installing replaces the whole runtime directory (issue #46); the
        // details have to survive it or the device silently stops being a
        // runner it was registered as.
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")
        val config = RunnerConfig(
            RunnerTarget.Repository("m96-chan", "DroidRunner"),
            "android-test-abc123",
            setOf("self-hosted", "android"),
        )
        RunnerRegistration.save(old, config)

        RunnerRegistration.copyDetails(old, fresh)

        assertTrue(RunnerRegistration.isConfigured(fresh))
        assertEquals(config, RunnerRegistration.load(fresh))
    }

    @Test fun theRunnersOwnIdentityIsLeftWithTheRuntimeItBelongsTo() {
        // .runner and .credentials are written by the runtime being replaced;
        // the service registers again from the copied details instead.
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")
        File(old, "home/runner").mkdirs()
        File(old, "home/runner/.runner").writeText("{}")
        File(old, "home/runner/.credentials").writeText("{}")
        RunnerRegistration.save(old, RunnerConfig(RunnerTarget.Organization("m96-chan"), "android-test", emptySet()))

        RunnerRegistration.copyDetails(old, fresh)

        assertFalse(RunnerRegistration.isRegistered(fresh))
    }

    @Test fun anUnregisteredDeviceCarriesNothingForward() {
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")

        RunnerRegistration.copyDetails(old, fresh)

        assertFalse(RunnerRegistration.isConfigured(fresh))
    }

    // --- leaving the previous repository (issue #154) ------------------------

    private fun config(owner: String, name: String) =
        RunnerConfig(RunnerTarget.Repository(owner, name), "android-test-abc123", setOf("android"))

    @Test fun movingToAnotherRepositoryLeavesTheFirstOne() {
        val detach = RunnerRegistration.targetToDetachFrom(
            stored = config("m96-chan", "DroidRunner"),
            wanted = config("m96-chan", "NxPU"),
        )

        assertEquals(RunnerTarget.Repository("m96-chan", "DroidRunner"), detach)
    }

    @Test fun registeringAgainstTheSameTargetIsNotAMove() {
        // `config.sh --replace` settles a same-target duplicate on its own.
        // Removing first would throw away a working registration to rebuild an
        // identical one, and would cost a runner id for nothing.
        assertNull(
            RunnerRegistration.targetToDetachFrom(
                stored = config("m96-chan", "DroidRunner"),
                wanted = config("m96-chan", "DroidRunner"),
            ),
        )
    }

    @Test fun aFirstRegistrationHasNothingToLeave() {
        assertNull(
            RunnerRegistration.targetToDetachFrom(
                stored = null,
                wanted = config("m96-chan", "DroidRunner"),
            ),
        )
    }

    @Test fun movingBetweenScopesCountsAsAMove() {
        // A device going from a repository to the organization that owns it is
        // still leaving an entry behind, and the two are different targets even
        // though the same jobs may reach it afterwards.
        val detach = RunnerRegistration.targetToDetachFrom(
            stored = config("m96-chan", "DroidRunner"),
            wanted = RunnerConfig(
                RunnerTarget.Organization("m96-chan"), "android-test-abc123", setOf("android"),
            ),
        )

        assertEquals(RunnerTarget.Repository("m96-chan", "DroidRunner"), detach)
    }

    // --- which credential registers (issue #194) -----------------------------

    // `register()` sends whatever this picks to `createRegistrationToken`, and
    // renews on a 401 only when it says the token is renewable.

    @Test fun aTypedPatWinsOverALiveSignIn() {
        // The whole point of the advanced panel: the user signed in to browse,
        // found the App is not installed on the repository they want, and
        // typed a PAT that does have the permission. Sending the sign-in
        // instead earns a 404 that says nothing about why.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "ghp_typed_by_hand",
            userToken = "gho_signed_in",
            pat = "ghp_typed_by_hand",
        )!!

        assertEquals("ghp_typed_by_hand", chosen.token)
    }

    @Test fun aPatIsNotWorthRenewing() {
        // There is nothing behind a hand-entered PAT to renew, so a 401 from
        // one is the answer rather than the start of a second attempt.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "ghp_typed_by_hand",
            userToken = "gho_signed_in",
            pat = null,
        )!!

        assertFalse(chosen.renewable)
    }

    @Test fun theSignInPassedByTheOtherButtonIsStillRenewable() {
        // The OAuth register button hands in the token it is holding, and that
        // token is the sign-in — so the 401 renewal still applies to it.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "gho_signed_in",
            userToken = "gho_signed_in",
            pat = null,
        )!!

        assertEquals("gho_signed_in", chosen.token)
        assertTrue(chosen.renewable)
    }

    @Test fun aCallerWithNothingInHandStillFallsBackToTheSignIn() {
        // RunnerService registering again after an ephemeral job passes no
        // credential, and must go on behaving exactly as it did.
        val chosen = RunnerRegistration.credentialFor(
            supplied = null,
            userToken = "gho_signed_in",
            pat = "ghp_stored",
        )!!

        assertEquals("gho_signed_in", chosen.token)
        assertTrue(chosen.renewable)
    }

    @Test fun theStoredPatIsTheLastResort() {
        val chosen = RunnerRegistration.credentialFor(
            supplied = null,
            userToken = null,
            pat = "ghp_stored",
        )!!

        assertEquals("ghp_stored", chosen.token)
        assertFalse(chosen.renewable)
    }

    @Test fun aDeviceWithNoCredentialAtAllHasNothingToTry() {
        assertNull(RunnerRegistration.credentialFor(null, null, null))
    }

    @Test fun aBlankFieldIsNotACredential() {
        // An empty PAT field would otherwise beat the sign-in and send an
        // empty Authorization header.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "",
            userToken = "gho_signed_in",
            pat = null,
        )!!

        assertEquals("gho_signed_in", chosen.token)
    }
}
