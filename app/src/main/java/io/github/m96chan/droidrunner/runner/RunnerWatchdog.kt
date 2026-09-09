package io.github.m96chan.droidrunner.runner

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.File

/**
 * Starts the service again from outside its own process (issue #184).
 *
 * `START_STICKY` is the documented answer to "bring my service back" and is
 * not enough on its own: on both phones this was measured on, a SIGKILL left
 * the process gone with no restart scheduled at all, which is what a vendor's
 * power management does to long-idle background work and what left a handset
 * silently not being a runner. Nothing inside the app can help once the app is
 * dead, so the watch has to live where the system keeps it.
 *
 * A `JobScheduler` job, because it is what actually ran: a repeating alarm was
 * tried first and, on the phone that reported the bug, was registered,
 * rescheduled every period and never once delivered in thirty-three minutes.
 * The job is persisted, so a reboot does not end the watch, and it is the
 * mechanism `WorkManager` sits on without the dependency.
 *
 * It does not check whether the service is running. Starting one that is
 * already up is free — [RunnerService.onStartCommand] returns early on a
 * redundant start — so the watchdog can be blunt and stay correct.
 *
 * It does not work everywhere. See [WatchdogJobService] for the ROM it does
 * not, and why nothing in the app can fix that one.
 */
object RunnerWatchdog {

    /**
     * The platform's own floor for this kind of work. Often enough that a dead
     * phone is measured in minutes rather than in however long until somebody
     * looks; asking for less would only be rounded up.
     */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /**
     * How late the check may be before the platform must run it anyway.
     *
     * This is the whole reason the watchdog is not a periodic job. A periodic
     * job is a request to run *some time* in its window, and on a Xiaomi it was
     * measured doing exactly that: it brought a killed runner back in sixteen
     * minutes once, and on the next identical attempt sat unrun for
     * thirty-three with `DEADLINE` its only unsatisfied constraint. A deadline
     * is a promise instead of a preference.
     */
    private const val DEADLINE_SLACK_MS = 60 * 1000L

    private const val JOB_ID = 8184

    /**
     * Books the next check, and is called again by every check that runs.
     *
     * One-shot rather than periodic, so each run has a deadline. That makes the
     * watch a chain rather than a subscription, which is only sound because a
     * link cannot be dropped: the platform has to run a job by its deadline,
     * and the job's first act is to book the next one.
     */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJobService::class.java))
                .setMinimumLatency(INTERVAL_MS)
                .setOverrideDeadline(INTERVAL_MS + DEADLINE_SLACK_MS)
                // Survives a reboot, so a phone that restarts overnight is
                // still being watched in the morning.
                .setPersisted(true)
                .build(),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /**
     * Whether the watchdog should do anything when it runs.
     *
     * Pure, and the same question the boot receiver asks: a device that never
     * registered has nothing to start, and one whose owner turned autostart off
     * asked not to be started.
     */
    fun shouldStart(configured: Boolean, runtimeInstalled: Boolean, autostart: Boolean): Boolean =
        configured && runtimeInstalled && autostart

    internal fun stateOf(context: Context): Triple<Boolean, Boolean, Boolean> {
        val runtime = RuntimeInstaller(context)
        return Triple(
            File(runtime.runtimeDir, ".configured").isFile,
            runtime.installed,
            context.getSharedPreferences("setup", Context.MODE_PRIVATE)
                .getBoolean("boot_autostart", true),
        )
    }
}
