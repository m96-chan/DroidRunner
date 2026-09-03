package io.github.m96chan.droidrunner.runner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RunnerState { STOPPED, STARTING, LISTENING, JOB_RUNNING, PAUSED }

data class RunnerSnapshot(
    val state: RunnerState = RunnerState.STOPPED,
    val startedAtMillis: Long? = null,
    val currentJob: String? = null,
    /** Why admission control is holding jobs, when [state] is PAUSED. */
    val pausedReason: String? = null,
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
        _snapshot.update {
            it.copy(
                jobsSucceeded = store.getInt("jobs_ok", 0),
                jobsFailed = store.getInt("jobs_fail", 0),
                bootGapMs = gapOf(context, readBootRecord(store), bootId()),
            )
        }
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
        prefs?.edit()
            ?.putInt("jobs_ok", snapshot.jobsSucceeded)
            ?.putInt("jobs_fail", snapshot.jobsFailed)
            ?.apply()
    }

    internal fun reset() {
        _snapshot.value = RunnerSnapshot()
        log = null
    }

    /** Points the log at a file of the test's choosing; production uses [attach]. */
    internal fun attachLog(replacement: RunnerLog?) {
        log = replacement
    }

    fun onServiceStarted() {
        _snapshot.update {
            RunnerSnapshot(
                state = RunnerState.STARTING,
                startedAtMillis = System.currentTimeMillis(),
                recentLog = it.recentLog,
                // The gap belongs to the boot, not to this run of the service,
                // so starting the runner again must not erase it.
                bootGapMs = it.bootGapMs,
            )
        }
    }

    /** Admission control is holding new jobs; the listener is stopped. */
    fun onPaused(reason: String) {
        _snapshot.update { it.copy(state = RunnerState.PAUSED, currentJob = null, pausedReason = reason) }
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
            it.copy(state = RunnerState.STOPPED, currentJob = null, startedAtMillis = null, pausedReason = null)
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

    @Synchronized
    private fun publish(line: String) {
        val wasRunning = _snapshot.value.state == RunnerState.JOB_RUNNING
        _snapshot.update { current ->
            var next = current.copy(recentLog = (current.recentLog + line).takeLast(LOG_LINES))
            when {
                line.contains("Listening for Jobs", ignoreCase = true) ->
                    next = next.copy(state = RunnerState.LISTENING, currentJob = null)
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
