package io.github.m96chan.droidrunner.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.m96chan.droidrunner.MainActivity
import io.github.m96chan.droidrunner.R

/**
 * Everything the service says to the user (issue #34).
 *
 * Two voices, kept apart on purpose. The ongoing notification always shows the
 * current state and never makes a sound; alerts are rare and mean a human has
 * to do something. Admission holds belong to the first: being unplugged is a
 * state, not an event, and a phone that buzzes every time it leaves a charger
 * is worse than one that says nothing.
 */
class RunnerNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "GitHub Runner", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows what the runner is doing while it is running." },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Runner problems", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = "Only things GitHub cannot tell you: the runner " +
                        "cannot start, or the device can no longer register."
                },
        )
    }

    fun ongoing(text: String): Notification = NotificationCompat.Builder(context, ONGOING_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_runner)
        .setContentTitle("DroidRunner")
        .setContentText(text)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    fun updateOngoing(text: String) {
        runCatching { manager.notify(ONGOING_ID, ongoing(text)) }
    }

    /** A one-shot notification for something that needs attention. */
    fun alert(title: String, text: String) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_runner)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        runCatching { manager.notify(ALERT_ID, notification) }
    }

    /** Takes back an alert once the thing it complained about started working. */
    fun clearAlert() {
        runCatching { manager.cancel(ALERT_ID) }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ONGOING_CHANNEL = "runner"
        const val ALERT_CHANNEL = "runner_alerts"
        const val ONGOING_ID = 96
        private const val ALERT_ID = 97

        /**
         * What the ongoing notification says for a given state. Pure, so the
         * wording is unit-tested rather than checked by eye on a device.
         *
         * A held runner has to say *why* it is held: from the outside, held and
         * broken look identical, and only the device knows the difference.
         */
        fun statusText(snapshot: RunnerSnapshot): String = snapshot.pausedReason
            ?.takeIf { snapshot.state != RunnerState.PAUSED }
            ?.let {
                if (snapshot.state == RunnerState.LISTENING || snapshot.state == RunnerState.JOB_RUNNING) {
                    "Condition: $it — still running"
                } else {
                    "Condition: $it"
                }
            }
            ?: when (snapshot.state) {
            RunnerState.STOPPED -> "Stopped"
            RunnerState.STARTING -> "Starting"
            RunnerState.LISTENING -> "Listening for jobs"
            RunnerState.JOB_RUNNING ->
                snapshot.currentJob?.let { "Running $it" } ?: "Running a job"
            RunnerState.PAUSED ->
                "Holding jobs: ${snapshot.pausedReason ?: "device is not ready"}"
            }
    }
}
