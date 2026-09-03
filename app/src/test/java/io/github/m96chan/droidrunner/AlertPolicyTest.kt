package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
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

    @Test fun aRefusedRenewalSpeaksUpTheFirstTime() {
        // Unlike a registration that might fail for a dozen transient reasons,
        // a refused refresh token is a decision GitHub will repeat: the device
        // is signed out until a person reconnects it.
        assertTrue(AlertPolicy.shouldAlert(AlertPolicy.Failure.SIGN_IN, 1, alreadyAlerted = false))
        assertTrue(
            AlertPolicy.SIGN_IN_FAILURES_BEFORE_ALERT <
                AlertPolicy.REGISTRATION_FAILURES_BEFORE_ALERT,
        )
    }

    @Test fun oneAlertPerStreakHoweverLongItRuns() {
        assertFalse(AlertPolicy.shouldAlert(AlertPolicy.Failure.LISTENER, 50, alreadyAlerted = true))
        assertFalse(
            AlertPolicy.shouldAlert(AlertPolicy.Failure.REGISTRATION, 9, alreadyAlerted = true),
        )
    }

    @Test fun escalatingFailuresCountAndLatchExactlyOneAlert() {
        var failures = 0
        var alerted = false
        val announcements = (1..8).count {
            val record = AlertPolicy.recordFailure(
                AlertPolicy.Failure.LISTENER,
                failures,
                alerted,
            )
            failures = record.consecutiveFailures
            alerted = record.alerted
            record.alertNow
        }

        assertTrue(alerted)
        assertEquals(8, failures)
        assertEquals(1, announcements)
    }
}
