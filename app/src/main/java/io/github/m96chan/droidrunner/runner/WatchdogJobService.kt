package io.github.m96chan.droidrunner.runner

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.util.Log

/**
 * The way back in after the system kills the app (issue #184).
 *
 * Measured on a Xiaomi 2511FPC34G (Android 16): after `kill -9`, this ran and
 * brought the runner back in sixteen minutes, one period. A repeating alarm was
 * built first and did not — on the phone that reported the bug it was
 * registered, rescheduled every period, and never once delivered — so only the
 * mechanism that was seen to work is kept.
 *
 * It does not work on every phone, and the failure is worth writing down
 * because no amount of app code fixes it. On a RedMagic 8 (ZTE) nothing brings
 * a killed runner back: not `START_STICKY`, not an alarm, and not this job with
 * every constraint satisfied and thirty-three minutes to run in. The app is not
 * force-stopped, sits in the EXEMPTED standby bucket, holds the battery
 * exemption, is not background-restricted and is not dozing — nothing in AOSP
 * explains it, and the vendor's own per-app power screen is behind a signature
 * permission the app cannot hold, so it cannot even send the owner there. Those
 * devices need the setting granted by hand.
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Very likely a process created for this job alone, so nothing has
        // attached the log yet and anything written would go nowhere.
        RunnerStatus.attach(this)
        // First, so the chain outlives anything that goes wrong below. A job
        // with a deadline runs once; if this run did not book the next one,
        // the watch would quietly end here.
        RunnerWatchdog.schedule(this)

        val (configured, installed, autostart) = RunnerWatchdog.stateOf(this)
        if (!RunnerWatchdog.shouldStart(configured, installed, autostart)) {
            say(
                "watchdog (job): nothing to start (configured=$configured " +
                    "installed=$installed autostart=$autostart)",
            )
            return false
        }
        runCatching {
            startForegroundService(Intent(this, RunnerService::class.java))
        }.onSuccess {
            say("watchdog (job): the runner was not running, started it")
        }.onFailure {
            say("watchdog (job): could not start the runner — ${it.message}")
        }
        // Nothing is left running: starting the service is the whole job.
        return false
    }

    /** Never reschedule: the next period comes round on its own. */
    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun say(line: String) {
        Log.i(TAG, line)
        runCatching { RunnerStatus.onAppLine(line) }
    }

    private companion object {
        const val TAG = "DroidRunnerWatchdog"
    }
}
