package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.github.GitHubApiException
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RegistrationCredentialSource
import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test fun anExplicitCredentialIsNotInferredToBeASignInFromItsValue() {
        // The sign-in button passes no token. An explicit token belongs to the
        // PAT flow even if it happens to equal the stored sign-in.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "gho_signed_in",
            userToken = "gho_signed_in",
            pat = null,
        )!!

        assertEquals("gho_signed_in", chosen.token)
        assertFalse(chosen.renewable)
        assertEquals(RegistrationCredentialSource.PAT, chosen.source)
    }

    @Test fun patSourceSurvivesRuntimeReplacementAndSelectsPatForTheNextJob() {
        val old = temp.newFolder("old-pat")
        val fresh = temp.newFolder("new-pat")
        val config = config("owner", "private").copy(credentialSource = RegistrationCredentialSource.PAT)
        RunnerRegistration.save(old, config)
        RunnerRegistration.copyDetails(old, fresh)
        val restored = RunnerRegistration.load(fresh)!!
        assertEquals(config, restored)
        val chosen = RunnerRegistration.resolveCredential(
            null, restored.credentialSource,
            userToken = { error("An unrelated expired App sign-in must not be read") },
            pat = "ghp_stored",
        )!!
        assertEquals("ghp_stored", chosen.token)
        assertFalse(chosen.renewable)
        assertFalse(File(fresh, "runner-config.json").readText().contains("ghp_stored"))
    }

    @Test fun anExplicitPatDoesNotRenewAnUnrelatedExpiredSignIn() {
        val chosen = RunnerRegistration.resolveCredential(
            "ghp_new", RegistrationCredentialSource.AUTO,
            userToken = { error("Must not renew the App sign-in") }, pat = "ghp_old",
        )!!
        assertEquals("ghp_new", chosen.token)
        assertEquals(RegistrationCredentialSource.PAT, chosen.source)
    }

    @Test fun missingSelectedPatDoesNotSilentlySwitchToSignIn() {
        assertNull(RunnerRegistration.resolveCredential(
            null, RegistrationCredentialSource.PAT,
            userToken = { error("Must not switch to sign-in") }, pat = null,
        ))
    }

    @Test fun signInSourceSurvivesReloadAndUsesTheRenewedToken() {
        val runtime = temp.newFolder("sign-in")
        RunnerRegistration.save(runtime, config("owner", "repo").copy(
            credentialSource = RegistrationCredentialSource.SIGN_IN,
        ))
        val chosen = RunnerRegistration.resolveCredential(
            null, RunnerRegistration.load(runtime)!!.credentialSource,
            userToken = { "gho_renewed" }, pat = "ghp_unrelated",
        )!!
        assertEquals("gho_renewed", chosen.token)
        assertTrue(chosen.renewable)
        assertEquals(RegistrationCredentialSource.SIGN_IN, chosen.source)
    }

    @Test fun missingSelectedSignInDoesNotSilentlySwitchToPat() {
        assertNull(RunnerRegistration.resolveCredential(
            null, RegistrationCredentialSource.SIGN_IN, userToken = { null }, pat = "ghp_unrelated",
        ))
    }

    @Test fun legacyDetailsKeepHistoricalFallbackAndLoadAllFields() {
        val runtime = temp.newFolder("legacy")
        File(runtime, "runner-config.json").writeText(
            """{"owner":"owner","repository":"repo","runnerName":"android-test-abc123","labels":["android"]}""",
        )
        val restored = RunnerRegistration.load(runtime)!!
        assertEquals(config("owner", "repo"), restored)
        assertEquals(RegistrationCredentialSource.AUTO, restored.credentialSource)
        assertEquals("gho_live", RunnerRegistration.resolveCredential(
            null, restored.credentialSource, userToken = { "gho_live" }, pat = "ghp_pat",
        )!!.token)
        assertEquals("ghp_pat", RunnerRegistration.resolveCredential(
            null, restored.credentialSource, userToken = { null }, pat = "ghp_pat",
        )!!.token)
    }

    @Test fun successfulRemovalDoesNotReadOrRenewTheFallback() {
        assertEquals("remove-token", RunnerRegistration.removalToken(
            "ghp_pat", signIn = { error("Must not read the fallback") }, request = { "remove-token" },
        ))
    }

    @Test fun refusedRemovalReadsTheLiveFallbackOnce() {
        var reads = 0
        val request = Removal("ghp_pat" to GitHubApiException(403, "forbidden"), "gho_renewed" to "remove-token")
        assertEquals("remove-token", RunnerRegistration.removalToken(
            "ghp_pat", signIn = { reads++; "gho_renewed" }, request = request,
        ))
        assertEquals(1, reads)
        assertEquals(listOf("ghp_pat", "gho_renewed"), request.tried)
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

    @Test fun passingNothingTakesTheLiveSignInAndKeepsItsRenewal() {
        // The sign-in path passes nothing on purpose. `register()` reads the
        // session itself, so the token is whatever it is *after* any renewal —
        // and it stays renewable, which is what #42's 401 recovery needs.
        val chosen = RunnerRegistration.credentialFor(
            supplied = null,
            userToken = "gho_renewed",
            pat = "ghp_pat",
        )!!

        assertEquals("gho_renewed", chosen.token)
        assertTrue(chosen.renewable)
    }

    // --- which credential leaves the old repository (issue #242) -------------

    // `detachFromPrevious` asks this for the removal token, and reports the
    // entry it could not remove when it throws.

    /** Records what was asked, and answers each credential as told. */
    private class Removal(vararg answers: Pair<String, Any>) : (String) -> String {
        private val answers = answers.toMap()
        val tried = mutableListOf<String>()

        override fun invoke(credential: String): String {
            tried += credential
            return when (val answer = answers[credential]) {
                is String -> answer
                is Throwable -> throw answer
                else -> throw IllegalStateException("nothing said about $credential")
            }
        }
    }

    @Test fun aPatForTheNewRepositoryLeavesTheOldOneWithTheSignIn() {
        // The advanced panel's PAT is scoped to the repository being joined, so
        // the repository being left refuses it. Before #194 this path used the
        // sign-in and worked; #242 is getting that back without giving up the
        // PAT for the registration itself.
        val request = Removal(
            "ghp_scoped_to_new" to GitHubApiException(403, "Resource not accessible"),
            "gho_signed_in" to "AAAA-REMOVAL",
        )

        val token = RunnerRegistration.removalToken("ghp_scoped_to_new", "gho_signed_in", request)

        assertEquals("AAAA-REMOVAL", token)
        assertEquals(listOf("ghp_scoped_to_new", "gho_signed_in"), request.tried)
    }

    @Test fun aPrivateRepositoryHiddenFromThePatIsStillWorthTheSignIn() {
        // GitHub answers 404 rather than 403 for a repository a token cannot
        // see, so the old target being private looks like it does not exist.
        val request = Removal(
            "ghp_scoped_to_new" to GitHubApiException(404, "Not Found"),
            "gho_signed_in" to "AAAA-REMOVAL",
        )

        assertEquals(
            "AAAA-REMOVAL",
            RunnerRegistration.removalToken("ghp_scoped_to_new", "gho_signed_in", request),
        )
    }

    @Test fun aDeviceWithNoSignInStillSaysWhatItLeftBehind() {
        // No second credential exists, so the refusal is the answer and the
        // caller reports which repository still holds an entry (#154).
        val request = Removal("ghp_scoped_to_new" to GitHubApiException(403, "Resource not accessible"))

        val refused = assertThrows(GitHubApiException::class.java) {
            RunnerRegistration.removalToken("ghp_scoped_to_new", signIn = null, request = request)
        }

        assertEquals(403, refused.status)
        assertEquals(listOf("ghp_scoped_to_new"), request.tried)
    }

    @Test fun aFaultThatIsNotAboutAuthorisationIsNotAskedTwice() {
        // GitHub being unwell answers the same for any credential. Asking
        // again delays the move and dresses an outage up as a permission
        // problem.
        val request = Removal("gho_signed_in" to GitHubApiException(500, "Server Error"))

        val failed = assertThrows(GitHubApiException::class.java) {
            RunnerRegistration.removalToken("gho_signed_in", "gho_other", request)
        }

        assertEquals(500, failed.status)
        assertEquals(listOf("gho_signed_in"), request.tried)
    }

    @Test fun theSignInThatIsAlreadyInHandIsNotAskedTwice() {
        // The OAuth path registers with the sign-in, so the fallback is the
        // same token. Sending it again is one more request for the same
        // refusal.
        val request = Removal("gho_signed_in" to GitHubApiException(403, "Resource not accessible"))

        assertThrows(GitHubApiException::class.java) {
            RunnerRegistration.removalToken("gho_signed_in", "gho_signed_in", request)
        }

        assertEquals(listOf("gho_signed_in"), request.tried)
    }

    @Test fun theSecondRefusalIsTheEndOfIt() {
        // Neither credential reaches the old target: two attempts, then the
        // message #154 wrote. Not a loop.
        val request = Removal(
            "ghp_scoped_to_new" to GitHubApiException(403, "Resource not accessible"),
            "gho_signed_in" to GitHubApiException(404, "Not Found"),
        )

        assertThrows(GitHubApiException::class.java) {
            RunnerRegistration.removalToken("ghp_scoped_to_new", "gho_signed_in", request)
        }

        assertEquals(listOf("ghp_scoped_to_new", "gho_signed_in"), request.tried)
    }

    @Test fun aRefusalIsAboutAuthorisationOnlyWhenGitHubSaidSo() {
        assertTrue(RunnerRegistration.refusedForAuthorisation(401))
        assertTrue(RunnerRegistration.refusedForAuthorisation(403))
        assertTrue(RunnerRegistration.refusedForAuthorisation(404))
        assertFalse(RunnerRegistration.refusedForAuthorisation(422))
        assertFalse(RunnerRegistration.refusedForAuthorisation(500))
        assertFalse(RunnerRegistration.refusedForAuthorisation(503))
    }

    @Test fun aTokenThatIsNotTheLiveSignInIsNotRenewable() {
        // The setup screen used to hand down its own cached copy of the
        // sign-in. Once the service renewed in the background that copy was
        // rotated out, so the request went with a dead token *and* was marked
        // unrenewable — GitHub 401s and there is no second chance. The screen
        // no longer passes it; this pins what happens if anything does.
        val chosen = RunnerRegistration.credentialFor(
            supplied = "gho_stale",
            userToken = "gho_renewed",
            pat = null,
        )!!

        assertEquals("gho_stale", chosen.token)
        assertFalse(chosen.renewable)
    }
}
