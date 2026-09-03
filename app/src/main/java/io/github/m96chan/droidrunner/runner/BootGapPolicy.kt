package io.github.m96chan.droidrunner.runner

/**
 * Works out how long a device was booted but not running anything (issue #41).
 *
 * `BOOT_COMPLETED` is withheld while the user is credential-locked and
 * delivered at the first unlock, so a phone with a secure lock screen does not
 * come back after a power cut until a human picks it up. Nothing in the app can
 * change that — both the runtime bundle and the stored credentials live in
 * credential-encrypted storage and are unreadable until then. What the app can
 * do is measure the wait afterwards and say so.
 *
 * Pure, so the arithmetic is settled here rather than by rebooting a phone.
 */
object BootGapPolicy {

    /**
     * Below this, the wait is the device booting rather than a device waiting
     * for a person. Android takes the better part of a minute to reach
     * `BOOT_COMPLETED` on a phone with no lock screen, and slower hardware
     * takes longer still; reporting that as an outage would make the line
     * meaningless on exactly the devices it is meant to reassure.
     */
    const val UNATTENDED_AFTER_MS = 3 * 60 * 1000L

    /** What the app remembers about one boot of the device. */
    data class BootRecord(
        /** The kernel's identity for that boot, so a later boot is a different one. */
        val bootId: String,
        /** How far into that boot the runner service first ran. */
        val startedAfterBootMs: Long,
    )

    /**
     * The record to keep once the service has started [uptimeMs] into [bootId].
     *
     * A second start within the same boot leaves the first one standing: only
     * the first measures the wait for the device to become usable. Later ones
     * measure how long somebody left the runner stopped, which is their own
     * business.
     */
    fun record(stored: BootRecord?, bootId: String, uptimeMs: Long): BootRecord =
        if (stored?.bootId == bootId) stored else BootRecord(bootId, uptimeMs.coerceAtLeast(0))

    /**
     * How long this boot went unserved, or null when there is nothing to report.
     *
     * Null covers three honest cases: the service has not run yet on this boot,
     * the wait was short enough to be the boot itself, and start-on-boot being
     * off — with the toggle off the device was never going to start by itself,
     * so the wait is the configuration working as asked, not a gap.
     */
    fun unattendedGapMs(
        stored: BootRecord?,
        bootId: String,
        startOnBootEnabled: Boolean,
    ): Long? {
        if (!startOnBootEnabled) return null
        if (stored == null || stored.bootId != bootId) return null
        return stored.startedAfterBootMs.takeIf { it >= UNATTENDED_AFTER_MS }
    }

    /**
     * A duration for a line of history: "12m", "2h 41m", "1d 3h". Whole
     * minutes, because by the time anyone reads this the gap has long closed
     * and the seconds never mattered.
     */
    fun describe(gapMs: Long): String {
        val minutes = gapMs / 60_000
        val days = minutes / (24 * 60)
        val hours = minutes % (24 * 60) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }
}
