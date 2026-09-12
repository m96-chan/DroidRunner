package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.github.GitHubApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four answers the release lookup can give, and what the panel does with
 * each (issues #193, #202).
 *
 * They used to be one null, and the screen read all of them as the build's
 * configuration. #193 gave the lookup a type that tells them apart; this is
 * the half that makes the screen act on the difference — so a dropped
 * connection offers Retry, and only a repository that genuinely publishes no
 * runtime sends the reader to `advanced`.
 *
 * And the version the resolved answer carries, which for a while nothing here
 * could see: the fetch went around the fake and out to the network, so it was
 * null in every one of these tests no matter what they said (issue #244).
 */
class ResolveRuntimeManifestTest {

    /**
     * Every URL the resolution asked for. Both of its requests come through
     * here now — the release feed and the manifest the feed names — where the
     * manifest fetch used to open its own connection to `example.invalid`
     * (issue #244). The fixture host stays `.invalid` for the same reason RFC
     * 6761 reserves it: if that bypass ever comes back, it cannot quietly
     * reach a real server instead.
     */
    private val requested = mutableListOf<String>()

    private fun api(body: (url: String) -> String) =
        GitHubApi { _, url, _, _ ->
            requested += url
            body(url)
        }

    private fun releases(vararg tags: String) =
        tags.joinToString(",", "[", "]") {
            """{"tag_name":"$it","assets":[{"name":"runtime-manifest.json",""" +
                """"browser_download_url":"https://example.invalid/$it/runtime-manifest.json"}]}"""
        }

    @Test fun aRepositoryWithNoRuntimeReleaseSendsYouToAdvanced() {
        val resolution = resolveRuntimeManifest(api { releases("v0.14.0", "v0.13.0") }, "o/r", null)

        resolution as ManifestResolution.NoRelease
        // The whole feed was read, so this really is "none" and not "none yet".
        assertEquals(false, resolution.truncated)
        assertTrue(runtimeUnavailableMessage(resolution).contains("advanced"))
    }

    @Test fun aBuildNamingNoRepositoryIsNotAskedAboutTheNetwork() {
        // Nothing is sent — the blank repo is answered before GitHub is asked.
        val resolution = resolveRuntimeManifest(api { error("GitHub must not be asked") }, "", null)

        assertEquals(ManifestResolution.NotConfigured, resolution)
    }

    @Test fun aDroppedConnectionIsNotAMissingRelease() {
        // The whole point of #202: this must not read as "this repository
        // publishes none", because that sends the reader somewhere useless.
        val resolution = resolveRuntimeManifest(
            api { throw java.io.IOException("Unable to resolve host \"api.github.com\"") },
            "o/r",
            null,
        )

        assertTrue(resolution is ManifestResolution.Unreachable)
        assertTrue(runtimeUnavailableMessage(resolution).contains("retry"))
    }

    @Test fun aReleaseFoundIsResolvedWithItsVersionAsWellAsItsUrl() {
        // The version is what #244 was about. This test always handed the fake
        // a manifest body and the fake was never asked for it, so the version
        // came back null whatever it said, and the assertion on the URL alone
        // kept the test green.
        val resolution = resolveRuntimeManifest(
            api { url ->
                if (url.contains("/releases")) {
                    releases("runtime-0.2.0")
                } else {
                    """{"version":"0.2.0"}"""
                }
            },
            "o/r",
            null,
        )

        resolution as ManifestResolution.Resolved
        assertEquals("https://example.invalid/runtime-0.2.0/runtime-manifest.json", resolution.url)
        assertEquals("0.2.0", resolution.version)
        // Two requests, and the second one is the manifest: that it was asked
        // of the fake at all is the thing that was missing.
        assertEquals(2, requested.size)
        assertEquals(resolution.url, requested[1])
    }

    @Test fun aManifestThatWillNotParseCostsTheVersionAndNothingElse() {
        // Best effort by design (#202): the version drives the "update
        // available" line, and losing it must not take the Install button with
        // it — a device with no runtime still has to be able to install one.
        val resolution = resolveRuntimeManifest(
            api { url ->
                if (url.contains("/releases")) releases("runtime-0.2.0") else "<html>502</html>"
            },
            "o/r",
            null,
        )

        resolution as ManifestResolution.Resolved
        assertEquals("https://example.invalid/runtime-0.2.0/runtime-manifest.json", resolution.url)
        assertEquals(null, resolution.version)
    }

    @Test fun aManifestFetchThatFailsIsStillAResolvedRelease() {
        // The same rule for the case the timeouts exist for: the manifest read
        // gave up, the release it was read from is still installable.
        val resolution = resolveRuntimeManifest(
            api { url ->
                if (url.contains("/releases")) {
                    releases("runtime-0.2.0")
                } else {
                    throw java.net.SocketTimeoutException("Read timed out")
                }
            },
            "o/r",
            null,
        )

        resolution as ManifestResolution.Resolved
        assertEquals(null, resolution.version)
    }

    @Test fun aScanThatHitItsPageCapSaysSoAndDoesNotOfferARetry() {
        // Folding this into `Unreachable` produced "could not reach GitHub to
        // look for a runtime release (no runtime release in the newest 500)"
        // for a lookup that plainly did reach GitHub — and offered a Retry
        // that runs the same scan for the same answer.
        val resolution = ManifestResolution.NoRelease(scanned = 500, truncated = true)
        val message = runtimeUnavailableMessage(resolution)

        assertTrue(message, message.contains("newest 500"))
        assertTrue(message, !message.contains("could not reach"))
        assertTrue(message, !message.contains("retry"))
    }
}
