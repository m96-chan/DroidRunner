package io.github.m96chan.droidrunner.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefreshPolicyTest {

    private val now = 1_700_000_000_000L
    private val eightHours = 8 * 60 * 60 * 1000L

    @Test fun aTokenGoodForHoursIsLeftAlone() {
        assertFalse(TokenRefreshPolicy.isDue(now + eightHours, now))
    }

    @Test fun aTokenIsRenewedWhileItStillWorks() {
        // The point of the margin: the renewal happens with a usable token in
        // hand, so nothing depends on it landing before the old one lapses.
        val expiresAt = now + TokenRefreshPolicy.REFRESH_MARGIN_MS - 1
        assertTrue(TokenRefreshPolicy.isDue(expiresAt, now))
    }

    @Test fun aTokenJustOutsideTheMarginCanWait() {
        val expiresAt = now + TokenRefreshPolicy.REFRESH_MARGIN_MS + 1
        assertFalse(TokenRefreshPolicy.isDue(expiresAt, now))
    }

    @Test fun aTokenThatAlreadyLapsedIsDue() {
        // Reached after the device slept through the expiry, which is the
        // ordinary case for a phone that took no jobs overnight.
        assertTrue(TokenRefreshPolicy.isDue(now - eightHours, now))
    }

    @Test fun aSignInWithNoKnownExpiryIsNeverDue() {
        // A GitHub App that opted out of expiration returns no lifetime, and so
        // does a sign-in stored before expiries were kept. Renewing on a guess
        // would spend a refresh token to replace a token that still works.
        assertFalse(TokenRefreshPolicy.isDue(null, now))
    }

    @Test fun anExpiryIsTheLifetimeGitHubGaveCountedFromNow() {
        assertEquals(now + eightHours, TokenRefreshPolicy.expiresAtMillis(28_800, now))
    }

    @Test fun aReplyWithoutALifetimeGetsNoExpiry() {
        // Absent and zero both mean "GitHub did not say"; neither should turn
        // into an expiry at the epoch, which would look permanently overdue.
        assertNull(TokenRefreshPolicy.expiresAtMillis(null, now))
        assertNull(TokenRefreshPolicy.expiresAtMillis(0, now))
    }
}
