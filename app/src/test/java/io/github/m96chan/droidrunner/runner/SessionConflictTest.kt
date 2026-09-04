package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConflictTest {

    /** The lines an app update actually produced, from the issue. */
    private val refusal = "A session for this runner already exists."
    private val retry =
        "2026-09-04 03:31:22Z: Runner connect error: Error: Conflict. Retrying until reconnected."

    @Test fun bothWaysGitHubSaysItAreRecognised() {
        assertTrue(SessionConflict.isConflict(refusal))
        assertTrue(SessionConflict.isConflict(retry))
    }

    @Test fun ordinaryStartupLinesAreNotAConflict() {
        // "Connected to GitHub" appears in the middle of the failing sequence,
        // so reading connection as success would end the wait early and hide
        // the very thing being reported.
        assertFalse(SessionConflict.isConflict("√ Connected to GitHub"))
        assertFalse(SessionConflict.isConflict("Runner reconnected."))
        assertFalse(SessionConflict.isConflict("Listening for Jobs"))
    }

    @Test fun onlyListeningEndsIt() {
        // It is the one line that means the listener got through; anything
        // less is a conflict that has not cleared.
        assertTrue(SessionConflict.isResolved("2026-09-04 03:32:01Z: Listening for Jobs"))
        assertFalse(SessionConflict.isResolved("√ Connected to GitHub"))
        assertFalse(SessionConflict.isResolved("Runner reconnected."))
    }

    @Test fun theListenerIsGivenTimeToSortItOutFirst() {
        // It usually does, well inside a minute, and a needless
        // re-registration costs a token and a new runner id.
        val started = 1_000_000L

        assertFalse(SessionConflict.shouldReplaceRunner(started, started))
        assertFalse(SessionConflict.shouldReplaceRunner(started, started + 30_000))
        assertTrue(SessionConflict.shouldReplaceRunner(started, started + 60_000))
        assertTrue(SessionConflict.shouldReplaceRunner(started, started + 300_000))
    }

    @Test fun withNoConflictThereIsNothingToEnd() {
        assertFalse(SessionConflict.shouldReplaceRunner(null, 1_000_000L))
    }

    @Test fun theScreenGetsASentenceRatherThanTheWordStarting() {
        // "starting" is also what a phone that cannot start at all says. This
        // is true, bounded, and about something the device did.
        val started = 1_000_000L

        assertEquals(
            "waiting for GitHub to release the previous session (12s)",
            SessionConflict.describe(started, started + 12_400),
        )
        assertNull(SessionConflict.describe(null, started))
    }

    @Test fun aClockThatWentBackwardsDoesNotProduceNegativeSeconds() {
        // The device clock can move under this; the sentence should stay a
        // sentence.
        assertEquals(
            "waiting for GitHub to release the previous session (0s)",
            SessionConflict.describe(1_000_000L, 999_000L),
        )
    }
}
