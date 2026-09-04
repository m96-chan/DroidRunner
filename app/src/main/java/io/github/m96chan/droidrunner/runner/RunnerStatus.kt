package io.github.m96chan.droidrunner.runner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RunnerState { STOPPED, STARTING, LISTENING, JOB_RUNNING, PAUSED }

data class RunnerSnapshot(
    val state: RunnerState = RunnerState.STOPPED,
    val startedAtMillis: Long? = null,
    val currentJob: String? = null,
    /** Current admission warning; the listener may still be active while it is confirmed. */
    val pausedReason: String? = null,
    /**
     * When GitHub first refused this listener's session, or null.
     *
     * Set after an app update, where the killed listener never released it
     * (issue #79). Kept as the moment it started so the screen can say how
     * long the wait has been rather than only that there is one.
     */
    val sessionHeldSince: Long? = null,
    val jobsSucceeded: Int = 0,
    val jobsFailed: Int = 0,
    /** How many times the supervisor restarted the listener this session. */
    val restarts: Int = 0,
    /**
     * How long this boot ran with no runner on it, when that was long enough
     * to mean the device sat locked (issue #41). History, not an alert.
     */
    val bootGapMs: Long? = null,
    val recentLog: List<String> = emptyList(),
)

/**
 * Where the lifetime job totals live between runs of the app: SharedPreferences
 * on a device, behind an interface so that the round trip can be exercised in a
 * unit test, which has no SharedPreferences to write to.
 */
internal interface JobCounterStore {
    /** The stored totals, succeeded first. */
    fun read(): Pair<Int, Int>
    fun write(succeeded: Int, failed: Int)
}

private class PrefsCounterStore(
    private val prefs: android.content.SharedPreferences,
) : JobCounterStore {
    override fun read(): Pair<Int, Int> = prefs.getInt("jobs_ok", 0) to prefs.getInt("jobs_fail", 0)

    override fun write(succeeded: Int, failed: Int) {
        prefs.edit().putInt("jobs_ok", succeeded).putInt("jobs_fail", failed).apply()
    }
}

/**
 * Shared runner state. RunnerService parses the official runner's listener output
 * and publishes transitions here; the dashboard collects [snapshot].
 *
 * Every line the device produces — the listener's and the app's own — passes
 * through here, so this is also where the log file is kept (issue #52). The
 * service owns the listener, but not the whole story: the setup screen
 * registers a runner before any service exists, and the lines explaining an
 * overnight pause have to survive the service that stopped. The log is
 * therefore held for as long as the process lives, opened from [attach]
 * alongside the rest of the state that outlives a single run.
 */
object RunnerStatus {
    private const val LOG_LINES = 100
    private const val BOOT_ID_KEY = "boot_id"
    private const val BOOT_STARTED_AFTER_KEY = "boot_started_after_ms"

    private val _snapshot = MutableStateFlow(RunnerSnapshot())
    val snapshot: StateFlow<RunnerSnapshot> = _snapshot

    private var prefs: android.content.SharedPreferences? = null
    private var counterStore: JobCounterStore? = null

    /**
     * The log on disk, or null before [attach]. Never closed: every append
     * flushes, so there is nothing buffered to lose, and the process dying is
     * the only end this file has.
     */
    @Volatile
    private var log: RunnerLog? = null

    /** Notified when a job starts (true) and when it finishes (false). */
    fun interface JobBoundaryListener {
        fun onJobActive(active: Boolean)
    }

    @Volatile
    private var jobListener: JobBoundaryListener? = null

    fun setJobBoundaryListener(listener: JobBoundaryListener?) {
        jobListener = listener
    }

    /**
     * Loads persisted lifetime job counters and the boot history; call once
     * from app entry points.
     */
    fun attach(context: android.content.Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("runner_stats", 0)
        prefs = store
        log = RunnerLog(context.applicationContext.filesDir)
        useCounterStore(PrefsCounterStore(store))
        _snapshot.update { it.copy(bootGapMs = gapOf(context, readBootRecord(store), bootId())) }
    }

    /** Installs the store the totals are kept in, and restores what it holds. */
    internal fun useCounterStore(store: JobCounterStore) {
        counterStore = store
        val (succeeded, failed) = store.read()
        _snapshot.update { it.copy(jobsSucceeded = succeeded, jobsFailed = failed) }
    }

    /**
     * Notes that the runner is running on this boot, and how far into the boot
     * it got there (issue #41).
     *
     * Only the service can record this: the gap being measured is exactly the
     * time before the service was able to run at all.
     */
    fun recordBootStart(context: android.content.Context) {
        val store = prefs ?: return
        val bootId = bootId()
        val record = BootGapPolicy.record(
            stored = readBootRecord(store),
            bootId = bootId,
            uptimeMs = android.os.SystemClock.elapsedRealtime(),
        )
        store.edit()
            .putString(BOOT_ID_KEY, record.bootId)
            .putLong(BOOT_STARTED_AFTER_KEY, record.startedAfterBootMs)
            .apply()
        _snapshot.update { it.copy(bootGapMs = gapOf(context, record, bootId)) }
    }

    private fun gapOf(
        context: android.content.Context,
        record: BootGapPolicy.BootRecord?,
        bootId: String,
    ): Long? = BootGapPolicy.unattendedGapMs(
        stored = record,
        bootId = bootId,
        startOnBootEnabled = context.applicationContext
            .getSharedPreferences("setup", android.content.Context.MODE_PRIVATE)
            .getBoolean("boot_autostart", true),
    )

    private fun readBootRecord(store: android.content.SharedPreferences): BootGapPolicy.BootRecord? {
        val id = store.getString(BOOT_ID_KEY, null) ?: return null
        return BootGapPolicy.BootRecord(id, store.getLong(BOOT_STARTED_AFTER_KEY, 0))
    }

    /**
     * The kernel's identity for the running boot. Where that file cannot be
     * read, the wall-clock moment the device booted stands in, to the minute:
     * the whole minute is there so that the clock being corrected shortly after
     * boot does not make this look like a second boot.
     */
    private fun bootId(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null }
    }.getOrNull() ?: run {
        val bootedAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        "booted-at-${bootedAt / 60_000}"
    }

    private fun persistCounts(snapshot: RunnerSnapshot) {
        counterStore?.write(snapshot.jobsSucceeded, snapshot.jobsFailed)
    }

    internal fun reset() {
        _snapshot.value = RunnerSnapshot()
        log = null
        counterStore = null
    }

    /** Points the log at a file of the test's choosing; production uses [attach]. */
    internal fun attachLog(replacement: RunnerLog?) {
        log = replacement
    }

    /**
     * A new run of the service, not a new device: only what describes a run is
     * rebuilt here, and the rest is carried over by copying rather than left to
     * whichever fields a freshly built snapshot happens to list. The lifetime
     * job totals most of all — stopping and starting is routine, admission
     * control does it whenever the device is unplugged or hot, and resetting
     * them here did not merely blank the display: the next job to finish wrote
     * the reset over the stored totals (issue #51). The boot gap belongs to the
     * boot and the log to the device, so both stay as well.
     */
    fun onServiceStarted() {
        _snapshot.update {
            it.copy(
                state = RunnerState.STARTING,
                startedAtMillis = System.currentTimeMillis(),
                currentJob = null,
                pausedReason = null,
                // Restarts are per-run by decision, not by omission: they count
                // the supervisor rescuing a listener that died, and carrying
                // them into a start the user asked for would read as a runner
                // that keeps falling over. The log still has the earlier ones.
                restarts = 0,
            )
        }
    }

    /** Admission control is holding new jobs; the listener is stopped. */
    fun onPaused(reason: String) {
        _snapshot.update { it.copy(state = RunnerState.PAUSED, currentJob = null, pausedReason = reason) }
    }

    /** Publishes a newly observed condition without pretending the listener has stopped. */
    fun onConditionObserved(reason: String) {
        _snapshot.update { it.copy(pausedReason = reason) }
    }

    /** Clears a momentary condition while preserving the live listener state. */
    fun onConditionRecovered() {
        _snapshot.update { it.copy(pausedReason = null) }
    }

    /** The supervisor is bringing the listener back after an exit. */
    fun onRestarting(reason: String) {
        _snapshot.update {
            it.copy(state = RunnerState.STARTING, currentJob = null, restarts = it.restarts + 1)
        }
        onAppLine("recovery: $reason")
    }

    /** Conditions recovered; the listener is starting again. */
    fun onResumed() {
        _snapshot.update { it.copy(state = RunnerState.STARTING, pausedReason = null) }
    }

    fun onServiceStopped() {
        _snapshot.update {
            it.copy(
                state = RunnerState.STOPPED,
                currentJob = null,
                startedAtMillis = null,
                pausedReason = null,
                sessionHeldSince = null,
            )
        }
    }

    /**
     * Marks the start of a listener attempt in the log file (issue #40). Not
     * state and not a line anyone said, so the dashboard's tail never sees it.
     */
    fun onListenerAttempt(startedAtMillis: Long) {
        log?.startAttempt(startedAtMillis)
    }

    /** A line the listener or the runner CLI printed, quoted as it came. */
    fun onRunnerLine(rawLine: String) = record(rawLine, RunnerLog.Source.RUNNER)

    /**
     * Something DroidRunner did, and why. These used to reach the in-memory
     * tail only, which meant the reason a device stopped taking jobs died with
     * the app process (issue #52).
     */
    fun onAppLine(rawLine: String) = record(rawLine, RunnerLog.Source.APP)

    private fun record(rawLine: String, source: RunnerLog.Source) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        // The file has a lock of its own. Writing outside this object's monitor
        // keeps per-line disk I/O off the path the dashboard's state takes,
        // which the listener can drive thousands of lines a minute.
        log?.append(line, source)
        publish(line)
    }

    /**
     * Forgets that a session was held, without claiming it was released.
     *
     * Used once the wait has been acted on or reported, so the same minute is
     * not announced again on every poll (issue #79).
     */
    fun onSessionWaitAcknowledged() {
        _snapshot.update { it.copy(sessionHeldSince = null) }
    }

    @Synchronized
    private fun publish(line: String) {
        val wasRunning = _snapshot.value.state == RunnerState.JOB_RUNNING
        _snapshot.update { current ->
            var next = current.copy(recentLog = (current.recentLog + line).takeLast(LOG_LINES))
            when {
                SessionConflict.isResolved(line) ->
                    next = next.copy(
                        state = RunnerState.LISTENING,
                        currentJob = null,
                        sessionHeldSince = null,
                    )
                // Only the first one starts the clock; the retry line repeats
                // every few seconds and would otherwise keep resetting it.
                SessionConflict.isConflict(line) && next.sessionHeldSince == null ->
                    next = next.copy(sessionHeldSince = System.currentTimeMillis())
                line.contains("Running job:", ignoreCase = true) ->
                    next = next.copy(
                        state = RunnerState.JOB_RUNNING,
                        currentJob = line.substringAfter("Running job:").trim().ifEmpty { null },
                    )
                line.contains("completed with result:", ignoreCase = true) -> {
                    val succeeded = line.substringAfter("completed with result:").trim()
                        .startsWith("Succeeded", ignoreCase = true)
                    next = next.copy(
                        state = RunnerState.LISTENING,
                        currentJob = null,
                        jobsSucceeded = next.jobsSucceeded + if (succeeded) 1 else 0,
                        jobsFailed = next.jobsFailed + if (succeeded) 0 else 1,
                    )
                    persistCounts(next)
                }
            }
            next
        }
        // Side effects run after the (retryable) update lambda, never inside it.
        val isRunning = _snapshot.value.state == RunnerState.JOB_RUNNING
        if (isRunning != wasRunning) jobListener?.onJobActive(isRunning)
    }
}
