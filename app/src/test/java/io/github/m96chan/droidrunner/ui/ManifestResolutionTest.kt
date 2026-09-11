package io.github.m96chan.droidrunner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the runtime panel says when there is no runtime to install (issue #202).
 *
 * All three used to arrive as one null, and the screen read every one of them
 * as the build's configuration: a phone that lost Wi-Fi for a second was told
 * to go and set a manifest URL under advanced, which would not have helped, and
 * the thing that would have — trying again — was not offered at all.
 */
class ManifestResolutionTest {

    @Test fun aDroppedConnectionBlamesTheConnection() {
        val message = runtimeUnavailableMessage(
            ManifestResolution.Unreachable("timeout"),
        )

        assertTrue(message, message.startsWith("could not reach GitHub"))
        assertTrue(message, message.contains("retry"))
    }

    @Test fun whatTheFailureSaidIsCarriedThrough() {
        // "timeout" and "Unable to resolve host" send someone to different
        // places, so the panel repeats whichever it got.
        val message = runtimeUnavailableMessage(
            ManifestResolution.Unreachable("Unable to resolve host \"api.github.com\""),
        )

        assertTrue(message, message.contains("Unable to resolve host"))
    }

    @Test fun aFailureThatSaidNothingStillReads() {
        val message = runtimeUnavailableMessage(ManifestResolution.Unreachable(null))

        assertEquals(
            "could not reach GitHub to look for a runtime release — " +
                "retry, or set a manifest URL under advanced",
            message,
        )
    }

    @Test fun anAnsweredRepositoryWithNoReleaseSaysSo() {
        val message = runtimeUnavailableMessage(ManifestResolution.NoRelease())

        assertTrue(message, message.contains("publishes no runtime release"))
        assertTrue(message, message.contains("advanced"))
    }

    @Test fun aBuildWithNoRuntimeRepositoryIsItsOwnAnswer() {
        // Nothing was asked, because there was nothing to ask. Telling this
        // user GitHub was unreachable would be a lie.
        val message = runtimeUnavailableMessage(ManifestResolution.NotConfigured)

        assertTrue(message, message.contains("names no runtime repository"))
    }

    @Test fun aBuildWithNoRuntimeRepositoryNeverStartsResolving() {
        assertEquals(ManifestResolution.NotConfigured, ManifestResolution.initial(""))
        assertEquals(ManifestResolution.NotConfigured, ManifestResolution.initial("   "))
    }

    @Test fun aBuildThatNamesOneStartsByAsking() {
        assertEquals(
            ManifestResolution.Resolving,
            ManifestResolution.initial("m96-chan/DroidRunner"),
        )
    }

    @Test fun theThreeOutcomesAreDistinctValuesAndNotOneNull() {
        // The point of the type: two of these once compared equal (null), and
        // the screen could only ever tell one story about all of them.
        val outcomes = listOf<ManifestResolution>(
            ManifestResolution.Resolved("https://example.invalid/m.json", null, "1.2.3"),
            ManifestResolution.NoRelease(),
            ManifestResolution.Unreachable("timeout"),
        )

        assertEquals(outcomes.size, outcomes.toSet().size)
        assertEquals(outcomes.size, outcomes.map(::runtimeUnavailableMessage).toSet().size)
    }
}
