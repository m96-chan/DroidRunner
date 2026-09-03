package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of these shapes is something api.github.com can answer with today.
 * They are worth pinning because the whole of this parsing runs only during
 * setup: when it breaks, the devices already registered keep taking jobs and
 * only a brand-new device fails, so the breakage goes unreported for weeks.
 */
class GitHubResponsesTest {

    // --- releases -> runtime manifest ------------------------------------

    private fun release(tag: String, vararg assets: String) = """
        {
          "tag_name": "$tag",
          "assets": [${assets.joinToString(",") { asset ->
        """{"name": "$asset", "browser_download_url": "https://example.test/$tag/$asset"}"""
    }}]
        }
    """.trimIndent()

    @Test fun theNewestRuntimeReleaseSuppliesTheManifestUrl() {
        val body = "[${release("runtime-2024.05", "runtime-manifest.json", "runtime.tar.zst")}," +
            "${release("runtime-2024.04", "runtime-manifest.json")}]"

        assertEquals(
            "https://example.test/runtime-2024.05/runtime-manifest.json",
            GitHubResponses.runtimeManifestUrl(body),
        )
    }

    @Test fun appReleasesAreNotMistakenForRuntimeReleases() {
        // The repo's releases page is mostly v* app builds, and those carry an
        // APK, not a runtime. Matching one would send the device off to
        // download an APK as if it were the runtime bundle.
        val body = "[${release("v1.4.0", "app-release.apk")},${release("v1.3.0", "app-release.apk")}]"

        assertNull(GitHubResponses.runtimeManifestUrl(body))
    }

    @Test fun aRuntimeReleaseStillMissingItsManifestFallsBackToTheOlderOne() {
        // A release is created before its assets finish uploading, so for a few
        // minutes on every runtime release the newest runtime-* tag has no
        // manifest. Setting up during that window has to keep working -- at the
        // cost of silently installing the previous runtime, which is why the
        // fallback is written down here rather than left to chance.
        val body = "[${release("runtime-2024.05")},${release("runtime-2024.04", "runtime-manifest.json")}]"

        assertEquals(
            "https://example.test/runtime-2024.04/runtime-manifest.json",
            GitHubResponses.runtimeManifestUrl(body),
        )
        assertEquals(
            "using runtime-2024.04 because runtime-2024.05 is not ready yet",
            GitHubResponses.runtimeManifest(body)?.fallbackNotice,
        )
    }

    @Test fun selectingTheNewestRuntimeNeedsNoFallbackNotice() {
        val selected = GitHubResponses.runtimeManifest(
            "[${release("runtime-2024.05", "runtime-manifest.json")}]",
        )

        assertNull(selected?.fallbackNotice)
    }

    @Test fun malformedReleasesAndAssetsDoNotHideALaterValidManifest() {
        val body = """[
            null,
            {"assets": []},
            {"tag_name": "runtime-broken"},
            {"tag_name": "runtime-also-broken", "assets": [null, {"name": "runtime-manifest.json"}]},
            ${release("runtime-2024.04", "runtime-manifest.json")}
        ]""".trimIndent()

        val selected = GitHubResponses.runtimeManifest(body)

        assertEquals("runtime-2024.04", selected?.tag)
        assertEquals("runtime-broken", selected?.newestRuntimeTag)
    }

    @Test fun aRuntimeReleaseCarryingEverythingButTheManifestYieldsNothing() {
        // Assets exist, just not the one that names the files to download.
        // Returning any other asset's URL would hand the installer a tarball
        // where it expects JSON.
        val body = "[${release("runtime-2024.05", "runtime.tar.zst", "runtime-manifest.json.sig")}]"

        assertNull(GitHubResponses.runtimeManifestUrl(body))
    }

    @Test fun aRepoWithNoReleasesAtAllYieldsNothing() {
        assertNull(GitHubResponses.runtimeManifestUrl("[]"))
    }

    // --- installations ---------------------------------------------------

    @Test fun installationsCarryTheirAccountTheAccountTypeAndTheAppSlug() {
        val body = """
            {"installations": [
              {"id": 42, "app_slug": "droidrunner",
               "account": {"login": "acme-inc", "type": "Organization"}},
              {"id": 7, "app_slug": "droidrunner",
               "account": {"login": "m96-chan", "type": "User"}}
            ]}
        """.trimIndent()

        val installations = GitHubResponses.installations(body)

        assertEquals(listOf(42L, 7L), installations.map { it.id })
        assertEquals(listOf("acme-inc", "m96-chan"), installations.map { it.account })
        assertEquals(listOf("Organization", "User"), installations.map { it.accountType })
        // The slug is remembered so the "install the app" button can deep-link
        // without a build-time GITHUB_APP_SLUG; losing it strands self-builders
        // on a dead link.
        assertEquals("droidrunner", installations.first().appSlug)
    }

    @Test fun anInstallationWhoseAccountIsGoneStillParses() {
        // GitHub sends "account": null for an installation whose owner has been
        // deleted or suspended. One of those in the list must not take the
        // whole repository picker down with it.
        val body = """
            {"installations": [
              {"id": 1, "app_slug": "droidrunner", "account": null},
              {"id": 2, "app_slug": "droidrunner"},
              {"id": 3, "app_slug": "droidrunner",
               "account": {"login": "acme-inc", "type": "Organization"}}
            ]}
        """.trimIndent()

        val installations = GitHubResponses.installations(body)

        assertEquals(3, installations.size)
        assertEquals("", installations[0].account)
        assertEquals("", installations[0].accountType)
        assertEquals("", installations[1].account)
        assertEquals("acme-inc", installations[2].account)
    }

    @Test fun malformedInstallationsDoNotHideValidOnes() {
        val body = """{"installations": [
            null,
            {"app_slug": "droidrunner"},
            {"id": 2},
            {"id": 3, "app_slug": "droidrunner", "account": {"login": "acme-inc"}}
        ]}"""

        val installations = GitHubResponses.installations(body)

        assertEquals(listOf(3L), installations.map { it.id })
        assertEquals("acme-inc", installations.single().account)
    }

    @Test fun onlyOrganizationAccountsBecomeOrganizationTargets() {
        // A personal account cannot host an org runner: offering one produces a
        // 404 at registration time, long after the user picked it.
        val installations = listOf(
            Installation(id = 1, account = "acme-inc", appSlug = "droidrunner", accountType = "Organization"),
            Installation(id = 2, account = "m96-chan", appSlug = "droidrunner", accountType = "User"),
            Installation(id = 3, account = "", appSlug = "droidrunner", accountType = ""),
        )

        assertEquals(
            listOf(RunnerTarget.Organization("acme-inc")),
            GitHubResponses.organizations(installations),
        )
    }

    // --- installation repositories ---------------------------------------

    private fun repositoriesBody(vararg fullNames: String) =
        """{"total_count": ${fullNames.size}, "repositories": [""" +
            fullNames.joinToString(",") { """{"full_name": "$it", "id": 1}""" } + "]}"

    @Test fun repositoriesAreSplitIntoOwnerAndName() {
        val page = GitHubResponses.repositoryPage(
            repositoriesBody("acme-inc/build-tools", "m96-chan/DroidRunner"),
        )

        assertEquals(
            listOf(RepositoryRef("acme-inc", "build-tools"), RepositoryRef("m96-chan", "DroidRunner")),
            page.repositories,
        )
    }

    @Test fun aPageThatIsNotFullIsTheLastPage() {
        // The stop condition for paging. If a short page claimed there was
        // more, setup would spend four more round trips on empty pages; the
        // user sees only a spinner that will not settle.
        val page = GitHubResponses.repositoryPage(repositoriesBody("acme-inc/build-tools"))

        assertFalse(page.hasMore)
    }

    @Test fun aFullPageMeansThereIsAnotherOneToFetch() {
        // An installation with more than 100 repositories is ordinary in an
        // org; stopping at the first full page hides everything after it and
        // the repo the user wants is simply absent from the picker.
        val full = repositoriesBody(*Array(GitHubResponses.PAGE_SIZE) { "acme-inc/repo-$it" })

        val page = GitHubResponses.repositoryPage(full)

        assertEquals(GitHubResponses.PAGE_SIZE, page.repositories.size)
        assertTrue(page.hasMore)
    }

    @Test fun anInstallationWithNoRepositoriesEndsThePaging() {
        val page = GitHubResponses.repositoryPage(repositoriesBody())

        assertTrue(page.repositories.isEmpty())
        assertFalse(page.hasMore)
    }

    // --- registration token ----------------------------------------------

    @Test fun theRegistrationTokenIsReadFromTheBody() {
        val body = """{"token": "AABF3JGZDX3P5PMEXLND6TS6FCWO6", "expires_at": "2024-05-01T12:00:00Z"}"""

        assertEquals("AABF3JGZDX3P5PMEXLND6TS6FCWO6", GitHubResponses.registrationToken(body))
    }

    // --- error bodies -----------------------------------------------------

    @Test fun aRefusalIsReportedInGitHubsOwnWords() {
        // "Bad credentials" is what tells the user to sign in again; a generic
        // "GitHub API 401" leaves them with nothing to act on.
        val body = """
            {"message": "Bad credentials",
             "documentation_url": "https://docs.github.com/rest"}
        """.trimIndent()

        assertEquals("GitHub API 401: Bad credentials", GitHubResponses.errorMessage(401, body))
    }

    @Test fun aBodyThatIsNotJsonIsShownAsIs() {
        // A proxy or GitHub's own edge can answer with HTML instead of JSON.
        // The text is ugly but it is the only evidence of what actually
        // refused the call, so it must not be swallowed.
        val html = "<html><body><h1>502 Bad Gateway</h1></body></html>"

        assertEquals("GitHub API 502: $html", GitHubResponses.errorMessage(502, html))
    }

    @Test fun validJsonWithoutAMessageIsShownAsIs() {
        val body = """{"error": "rate limit exceeded"}"""

        assertEquals("GitHub API 403: $body", GitHubResponses.errorMessage(403, body))
    }
}
