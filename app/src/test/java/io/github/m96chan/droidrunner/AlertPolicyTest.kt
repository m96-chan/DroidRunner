package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    @Test fun aSingleListenerFailureIsNotWorthSaying() {
        // The supervisor recovers from most exits on its own; announcing every
        // restart would train the user to swipe DroidRunner away.
        assertFalse(AlertPolicy.shouldAlert(AlertPolicy.Failure.LISTENER, 1, alreadyAlerted = false))
        assertFalse(AlertPolicy.shouldAlert(AlertPolicy.Failure.LISTENER, 3, alreadyAlerted = false))
    }

    @Test fun aListenerThatKeepsFailingSpeaksUp() {
        assertTrue(
            AlertPolicy.shouldAlert(
                AlertPolicy.Failure.LISTENER,
                AlertPolicy.LISTENER_FAILURES_BEFORE_ALERT,
                alreadyAlerted = false,
            ),
        )
    }

    @Test fun registrationSpeaksUpSoonerThanTheListener() {
        // An expired sign-in never fixes itself, so waiting out four backoffs
        // only delays the one action that helps.
        assertTrue(
            AlertPolicy.REGISTRATION_FAILURES_BEFORE_ALERT <
                AlertPolicy.LISTENER_FAILURES_BEFORE_ALERT,
        )
        assertTrue(
            AlertPolicy.shouldAlert(
                AlertPolicy.Failure.REGISTRATION,
                AlertPolicy.REGISTRATION_FAILURES_BEFORE_ALERT,
                alreadyAlerted = false,
            ),
        )
    }

    @Test fun oneAlertPerStreakHoweverLongItRuns() {
        assertFalse(AlertPolicy.shouldAlert(AlertPolicy.Failure.LISTENER, 50, alreadyAlerted = true))
        assertFalse(
            AlertPolicy.shouldAlert(AlertPolicy.Failure.REGISTRATION, 9, alreadyAlerted = true),
        )
    }
}
