package io.github.m96chan.droidrunner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the About screen offers as the source for the binaries it ships
 * (issue #226).
 *
 * The person the offer is for is holding the APK and nothing else, so the URL
 * has to be right without anyone there to check it: it is rebuilt from the
 * version name, and the build that most often has this screen open — a local
 * one — has no release behind it at all. Getting that case wrong answers the
 * only question this panel exists to answer with a 404.
 */
class SourceOfferTest {

    private val prootCommit = "0123456789abcdef0123456789abcdef01234567"

    private fun offerFor(versionName: String, gitCommit: String = "abc1234") =
        sourceOffer(versionName, prootCommit, gitCommit)

    @Test fun aReleaseBuildNamesTheArchivePublishedBesideIt() {
        val offer = offerFor("0.7.0")

        assertTrue(offer.text, offer.text.contains("droidrunner-v0.7.0-source.tar.gz"))
        assertTrue(offer.text, offer.text.contains("v0.7.0 release"))
    }

    @Test fun theLinkIsTheReleaseAssetForTheTagTheBuildCameFrom() {
        // versionName is the tag with its "v" removed; the asset keeps the tag.
        val offer = offerFor("1.12.3")

        assertEquals("source archive", offer.linkLabel)
        assertEquals(
            "https://github.com/m96-chan/DroidRunner/releases/download/v1.12.3/" +
                "droidrunner-v1.12.3-source.tar.gz",
            offer.url,
        )
    }

    @Test fun aDevBuildSaysSoRatherThanLinkingToAReleaseNobodyCut() {
        val offer = offerFor("0.0.0-dev")

        assertTrue(offer.text, offer.text.contains("not a release"))
        assertFalse(offer.url, offer.url.contains("/releases/download/"))
        assertFalse(offer.text, offer.text.contains("droidrunner-0.0.0-dev-source.tar.gz"))
    }

    @Test fun aDevBuildOffersTheCommitItWasBuiltFrom() {
        // Which is the corresponding source for it; there is no other copy.
        val offer = offerFor("0.0.0-dev")

        assertTrue(offer.text, offer.text.contains("commit abc1234"))
        assertEquals("source at abc1234", offer.linkLabel)
        assertEquals("https://github.com/m96-chan/DroidRunner/tree/abc1234", offer.url)
    }

    @Test fun aBuildWithNoGitOffersTheRepositoryAndNoDanglingCommit() {
        // GIT_COMMIT is empty when there was no git to ask — building the app
        // from the source archive is exactly that case — and the text must not
        // then read "from commit " with nothing after it.
        val offer = offerFor("0.0.0-dev", gitCommit = "")

        assertFalse(offer.text, offer.text.contains("from commit"))
        assertEquals("project source", offer.linkLabel)
        assertEquals("https://github.com/m96-chan/DroidRunner", offer.url)
    }

    @Test fun everyBuildNamesTheProotCommitItActuallyShips() {
        // Short enough to read off a screen, long enough to name a commit.
        for (version in listOf("0.7.0", "0.0.0-dev")) {
            assertTrue(version, offerFor(version).text.contains("0123456789ab"))
        }
    }

    @Test fun theRootfsPointerNamesBothFilesTheBundleCarries() {
        // PACKAGES.txt is what is in the rootfs, SOURCE-OFFER.txt is how to get
        // its source; runtime/build-bundle.sh writes both into the tarball.
        assertTrue(ROOTFS_SOURCE, ROOTFS_SOURCE.contains("PACKAGES.txt"))
        assertTrue(ROOTFS_SOURCE, ROOTFS_SOURCE.contains("SOURCE-OFFER.txt"))
    }
}
