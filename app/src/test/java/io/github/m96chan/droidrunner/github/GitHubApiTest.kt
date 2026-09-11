package io.github.m96chan.droidrunner.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many pages get asked for, and what the answer is when the thing being
 * looked for is not in them.
 *
 * [GitHubResponsesTest] pins what one hand-written body parses into, which is
 * exactly what both #193 and #201 slipped past: the bodies were read correctly
 * and the app simply stopped reading too early. So these drive [GitHubApi]
 * with a substituted sender and assert on the requests it made.
 */
class GitHubApiTest {

    /**
     * Serves page N of one endpoint and records every URL asked for. A request
     * for a page that was not supplied fails the test rather than answering
     * emptily, so "it asked for one page too many" is as visible as one too few.
     */
    private class Feed(private vararg val pages: String) {
        val requested = mutableListOf<String>()

        fun api() = GitHubApi { _, url, _, _ ->
            requested += url
            // Anchored on the separator, or it reads the 100 out of per_page.
            val page = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
            pages.getOrNull(page - 1) ?: throw AssertionError("asked for an unexpected page: $url")
        }
    }

    // --- the runtime release, however deep it has sunk (issue #193) --------

    private fun release(tag: String, vararg assets: String) = """
        {
          "tag_name": "$tag",
          "assets": [${assets.joinToString(",") { asset ->
        """{"name": "$asset", "browser_download_url": "https://example.test/$tag/$asset"}"""
    }}]
        }
    """.trimIndent()

    private fun feedPage(vararg releases: String) = "[${releases.joinToString(",")}]"

    /** [count] app releases, which is what the feed is mostly made of. */
    private fun appReleases(count: Int) = Array(count) { release("v1.$it.0", "app-release.apk") }

    @Test fun aRuntimeReleaseOnlyOnTheSecondPageIsStillResolved() {
        // The shape of the bug: a hundred app releases published since the
        // runtime bundle, pushing it off the only page that used to be read.
        val feed = Feed(
            feedPage(*appReleases(GitHubResponses.PAGE_SIZE)),
            feedPage(release("runtime-0.2.0", "runtime-manifest.json", "runtime.tar.zst")),
        )

        val result = feed.api().latestRuntimeRelease("m96-chan/DroidRunner", null)

        assertEquals(
            RuntimeReleaseResult.Found(
                RuntimeManifestRelease(
                    url = "https://example.test/runtime-0.2.0/runtime-manifest.json",
                    tag = "runtime-0.2.0",
                    newestRuntimeTag = "runtime-0.2.0",
                ),
            ),
            result,
        )
        assertEquals(2, feed.requested.size)
        assertTrue(feed.requested.first().contains("per_page=${GitHubResponses.PAGE_SIZE}"))
    }

    @Test fun aRuntimeTagSeenOnAnEarlierPageStillExplainsTheFallback() {
        // The assets-still-uploading window, now spread over two requests: the
        // tag with no manifest is on page one and the manifest that gets
        // installed instead is on page two. The notice has to name both.
        val feed = Feed(
            feedPage(release("runtime-0.3.0"), *appReleases(GitHubResponses.PAGE_SIZE - 1)),
            feedPage(release("runtime-0.2.0", "runtime-manifest.json")),
        )

        val found = feed.api().latestRuntimeRelease("m96-chan/DroidRunner", null)

        assertEquals(
            "using runtime-0.2.0 because runtime-0.3.0 is not ready yet",
            (found as RuntimeReleaseResult.Found).release.fallbackNotice,
        )
    }

    @Test fun aFeedOfNothingButAppReleasesReportsNoneFoundNotAFailure() {
        // Nothing went wrong here and the build is configured: the repository
        // has published no runtime bundle. Telling the user to set a manifest
        // URL under advanced, as a null used to, points at the wrong thing.
        val feed = Feed(feedPage(*appReleases(3)))

        assertEquals(
            RuntimeReleaseResult.NoneFound(scanned = 3, truncated = false),
            feed.api().latestRuntimeRelease("m96-chan/DroidRunner", null),
        )
    }

    @Test fun aBuildWithNoRuntimeRepoAsksGitHubNothing() {
        val feed = Feed()

        assertEquals(
            RuntimeReleaseResult.NotConfigured,
            feed.api().latestRuntimeRelease("", null),
        )
        assertTrue(feed.requested.isEmpty())
    }

    @Test fun aRefusedRequestIsAFailureRatherThanAnEmptyFeed() {
        // An unauthenticated device that has run into the rate limit has a
        // runtime release waiting for it; it just could not be asked for.
        val api = GitHubApi { _, _, _, _ ->
            throw GitHubApiException(403, "GitHub API 403: API rate limit exceeded")
        }

        assertEquals(
            RuntimeReleaseResult.Failed(403, "GitHub API 403: API rate limit exceeded"),
            api.latestRuntimeRelease("m96-chan/DroidRunner", null),
        )
    }

    @Test fun aBodyThatIsNotAReleaseFeedIsAFailureToo() {
        val api = GitHubApi { _, _, _, _ -> "<html>502 Bad Gateway</html>" }

        val result = api.latestRuntimeRelease("m96-chan/DroidRunner", null)

        assertTrue("$result", result is RuntimeReleaseResult.Failed)
        assertEquals(null, (result as RuntimeReleaseResult.Failed).status)
    }

    @Test fun theReleaseScanStopsAtItsPageCapAndSaysItStopped() {
        // Five hundred releases deep, the honest answer is "not in the ones I
        // looked at", which is not quite the same as "there is none".
        val feed = Feed(*Array(5) { feedPage(*appReleases(GitHubResponses.PAGE_SIZE)) })

        assertEquals(
            RuntimeReleaseResult.NoneFound(scanned = 500, truncated = true),
            feed.api().latestRuntimeRelease("m96-chan/DroidRunner", null),
        )
        assertEquals(5, feed.requested.size)
    }

    @Test fun theOlderManifestUrlAccessorStillAnswers() {
        val feed = Feed(
            feedPage(*appReleases(GitHubResponses.PAGE_SIZE)),
            feedPage(release("runtime-0.2.0", "runtime-manifest.json")),
        )

        assertEquals(
            "https://example.test/runtime-0.2.0/runtime-manifest.json",
            feed.api().latestRuntimeManifestUrl("m96-chan/DroidRunner", null),
        )
    }

    // --- installations, all of them (issue #201) --------------------------

    private fun installationsPage(ids: List<Int>) =
        """{"total_count": ${ids.size}, "installations": [""" +
            ids.joinToString(",") {
                """{"id": $it, "app_slug": "droidrunner",
                    "account": {"login": "org-$it", "type": "Organization"}}"""
            } + "]}"

    @Test fun aSecondPageOfInstallationsIsFetched() {
        // A user in more than a hundred installations was losing whole
        // organisations, and the picker looked like it had finished loading.
        val feed = Feed(
            installationsPage((1..GitHubResponses.PAGE_SIZE).toList()),
            installationsPage((101..120).toList()),
        )

        val installations = feed.api().listInstallations("token")

        assertEquals(120, installations.size)
        assertEquals(120L, installations.last().id)
        assertEquals(2, feed.requested.size)
        assertTrue(feed.requested[1].contains("page=2"))
    }

    @Test fun aShortPageOfInstallationsIsTheLastRequest() {
        val feed = Feed(installationsPage(listOf(1, 2, 3)))

        assertEquals(3, feed.api().listInstallations("token").size)
        assertEquals(1, feed.requested.size)
    }

    @Test fun organizationsComeFromEveryPageOfInstallations() {
        val feed = Feed(
            installationsPage((1..GitHubResponses.PAGE_SIZE).toList()),
            installationsPage(listOf(101)),
        )

        assertTrue(feed.api().listOrganizations("token").any { it.org == "org-101" })
    }

    // --- repositories, and saying when they ran out (issue #201) ----------

    private fun repositoriesPage(count: Int, from: Int = 0) =
        """{"total_count": $count, "repositories": [""" +
            (from until from + count).joinToString(",") {
                """{"full_name": "acme-inc/repo-$it", "id": $it}"""
            } + "]}"

    @Test fun aTruncatedRepositoryListSurfacesItsTruncation() {
        // Five full pages and GitHub still has more. The list is usable and it
        // is not complete, and the caller has to be able to tell the user so.
        val feed = Feed(*Array(5) { repositoriesPage(GitHubResponses.PAGE_SIZE, it * 100) })

        val repositories = feed.api().installationRepositories("token", 42L)

        assertTrue(repositories.truncated)
        assertEquals(500, repositories.repositories.size)
        assertEquals(5, feed.requested.size)
    }

    @Test fun aRepositoryListThatRanOutIsNotTruncated() {
        val feed = Feed(
            repositoriesPage(GitHubResponses.PAGE_SIZE),
            repositoriesPage(7, from = 100),
        )

        val repositories = feed.api().installationRepositories("token", 42L)

        assertFalse(repositories.truncated)
        assertEquals(107, repositories.repositories.size)
    }

    @Test fun thePlainRepositoryListStillReturnsEveryPageItFetched() {
        val feed = Feed(
            repositoriesPage(GitHubResponses.PAGE_SIZE),
            repositoriesPage(7, from = 100),
        )

        assertEquals(107, feed.api().listInstallationRepositories("token", 42L).size)
    }
}
