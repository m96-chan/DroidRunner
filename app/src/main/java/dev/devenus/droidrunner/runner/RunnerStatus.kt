package dev.devenus.droidrunner.runner

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
    val recentLog: List<String> = emptyList(),
)

/**
 * Shared runner state. RunnerService parses the official runner's listener output
 * and publishes transitions here; the dashboard collects [snapshot].
 */
object RunnerStatus {
    private const val LOG_LINES = 100

    private val _snapshot = MutableStateFlow(RunnerSnapshot())
    val snapshot: StateFlow<RunnerSnapshot> = _snapshot

    private var prefs: android.content.SharedPreferences? = null

    /** Notified when a job starts (true) and when it finishes (false). */
    fun interface JobBoundaryListener {
        fun onJobActive(active: Boolean)
    }

    @Volatile
    private var jobListener: JobBoundaryListener? = null

    fun setJobBoundaryListener(listener: JobBoundaryListener?) {
        jobListener = listener
    }

    /** Loads persisted lifetime job counters; call once from app entry points. */
    fun attach(context: android.content.Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("runner_stats", 0)
        prefs = store
        _snapshot.update {
            it.copy(
                jobsSucceeded = store.getInt("jobs_ok", 0),
                jobsFailed = store.getInt("jobs_fail", 0),
            )
        }
    }

    private fun persistCounts(snapshot: RunnerSnapshot) {
        prefs?.edit()
            ?.putInt("jobs_ok", snapshot.jobsSucceeded)
            ?.putInt("jobs_fail", snapshot.jobsFailed)
            ?.apply()
    }

    internal fun reset() {
        _snapshot.value = RunnerSnapshot()
    }

    fun onServiceStarted() {
        _snapshot.update {
            RunnerSnapshot(
                state = RunnerState.STARTING,
                startedAtMillis = System.currentTimeMillis(),
                recentLog = it.recentLog,
            )
        }
    }

    /** Admission control is holding new jobs; the listener is stopped. */
    fun onPaused(reason: String) {
        _snapshot.update { it.copy(state = RunnerState.PAUSED, currentJob = null, pausedReason = reason) }
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

    @Synchronized
    fun onLogLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
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
