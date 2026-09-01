package dev.devenus.droidrunner.runner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RunnerState { STOPPED, STARTING, LISTENING, JOB_RUNNING }

data class RunnerSnapshot(
    val state: RunnerState = RunnerState.STOPPED,
    val startedAtMillis: Long? = null,
    val currentJob: String? = null,
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

    fun onServiceStopped() {
        _snapshot.update { it.copy(state = RunnerState.STOPPED, currentJob = null, startedAtMillis = null) }
    }

    fun onLogLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
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
    }
}
