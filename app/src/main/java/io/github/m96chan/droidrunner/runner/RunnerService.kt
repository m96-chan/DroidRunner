package io.github.m96chan.droidrunner.runner

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import io.github.m96chan.droidrunner.monitor.SystemMonitor
import io.github.m96chan.droidrunner.npu.DeviceAgentServer
import io.github.m96chan.droidrunner.npu.DeviceCapabilitiesJson
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the official runner alive under a foreground service, and holds new
 * work when the device is in no shape to take it (issue #2).
 *
 * A supervisor loop owns the listener process: it starts it while conditions
 * allow, and stops it between jobs when they do not, so a build is never
 * killed halfway — except in the critical thermal range, where continuing
 * risks the hardware.
 */
class RunnerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var agent: DeviceAgentServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val starting = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val jobRunning = AtomicBoolean(false)
    private lateinit var monitor: SystemMonitor
    private lateinit var notifications: RunnerNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Restart backoff state, owned by the supervisor and the output thread. */
    @Volatile private var restartDelayMs = 0L
    @Volatile private var nextStartAtMillis = 0L

    /** Alert state for issue #34: one notification per streak of failures. */
    @Volatile private var consecutiveFailures = 0
    @Volatile private var alerted = false

    override fun onCreate() {
        super.onCreate()
        RunnerStatus.attach(this)
        monitor = SystemMonitor(this)
        notifications = RunnerNotifications(this).also { it.createChannels() }

        // The notification says whatever the dashboard says; it reads the same
        // state and only redraws when the wording would change, so a busy log
        // does not mean a notification update per line.
        scope.launch {
            RunnerStatus.snapshot
                .map { RunnerNotifications.statusText(it) }
                .distinctUntilChanged()
                .collect { notifications.updateOngoing(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRunner()
            return START_NOT_STICKY
        }
        startForeground(
            RunnerNotifications.ONGOING_ID,
            notifications.ongoing(RunnerNotifications.statusText(RunnerStatus.snapshot.value)),
        )
        if (!starting.compareAndSet(false, true)) return START_NOT_STICKY
        stopRequested.set(false)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidRunner:Runner").also { it.acquire() }
        RunnerStatus.onServiceStarted()

        // Device Agent: loopback bridge that jobs use to reach Android-side
        // hardware (NNAPI); URL and token file are injected into the runner
        // environment, which jobs inherit.
        val runtimeDir = RuntimeInstaller(this).runtimeDir
        agent = DeviceAgentServer(
            runtimeDir = runtimeDir,
            capabilitiesJson = { DeviceCapabilitiesJson.build(this) },
        ).also { server ->
            server.start()
            RunnerStatus.setJobBoundaryListener { active ->
                jobRunning.set(active)
                // Fresh capability token per job; revoked when the job ends.
                server.onJobActive(active)
            }
        }

        executor.execute { supervise(runtimeDir) }
        return START_NOT_STICKY
    }

    /** Starts, holds, and restarts the listener according to device conditions. */
    private fun supervise(runtimeDir: File) {
        runCatching {
            check(RunnerRegistration.isConfigured(runtimeDir)) { "Runner is not configured" }
            var pausedFor: String? = null

            while (!stopRequested.get()) {
                val thresholds = AdmissionThresholds.load(this)
                val decision = AdmissionPolicy.evaluate(sampleConditions(), thresholds)

                when {
                    decision is Admission.Allowed -> {
                        if (pausedFor != null) {
                            RunnerStatus.onResumed()
                            RunnerStatus.onLogLine("admission: conditions recovered, resuming")
                            pausedFor = null
                        }
                        if (process == null && System.currentTimeMillis() >= nextStartAtMillis) {
                            launchListener(runtimeDir)
                        }
                    }

                    decision is Admission.Blocked -> {
                        // Hold between jobs; only heat interrupts a running one.
                        val mayStop = !jobRunning.get() || decision.urgent
                        if (process != null && mayStop) {
                            RunnerStatus.onLogLine(
                                "admission: ${decision.reason}" +
                                    if (decision.urgent && jobRunning.get()) " — stopping active job" else "",
                            )
                            stopListener()
                        }
                        if (process == null && pausedFor != decision.reason) {
                            pausedFor = decision.reason
                            RunnerStatus.onPaused(decision.reason)
                        }
                    }
                }
                // Short sleep so a due restart is honoured promptly; the
                // condition sampling itself is cheap.
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }.onFailure {
            if (it !is InterruptedException) RunnerStatus.onLogLine("runner error: ${it.message}")
        }
        starting.set(false)
        if (!stopRequested.get()) stopSelf()
    }

    private fun sampleConditions(): DeviceConditions {
        val system = monitor.sample()
        return DeviceConditions(
            charging = system.charging,
            batteryPercent = system.batteryPercent,
            thermalStatus = system.thermalStatus,
            freeStorageMb = (system.diskTotalBytes - system.diskUsedBytes) / (1024 * 1024),
        )
    }

    /**
     * Registers if needed (always, for ephemeral runners, since the previous
     * job consumed the registration) and starts the listener.
     */
    private fun launchListener(runtimeDir: File) {
        // Nothing of ours is running at this point, so anything still holding a
        // session belongs to a previous app process and would only make this
        // start fail on a conflict.
        stopStrayListeners()
        val ephemeral = RunnerRegistration.ephemeralEnabled(this)
        // Re-register when the mode changed: an existing persistent
        // registration would otherwise keep serving jobs forever.
        val modeChanged = RunnerRegistration.isRegistered(runtimeDir) &&
            RunnerRegistration.registeredAsEphemeral(runtimeDir) != ephemeral
        if (!RunnerRegistration.isRegistered(runtimeDir) || modeChanged) {
            val config = RunnerRegistration.load(runtimeDir)
            if (config == null) {
                // Configured before this build, without the stored details
                // needed to re-register; the existing registration still works.
                if (!ephemeral) return startListener(runtimeDir)
                RunnerStatus.onLogLine(
                    "ephemeral: re-register once from the setup screen to enable per-job registration",
                )
                backOff(
                    "missing stored registration details",
                    ranMillis = 0,
                    failure = AlertPolicy.Failure.REGISTRATION,
                )
                return
            }
            val outcome = runCatching {
                if (ephemeral) RunnerRegistration.cleanWorkDirectory(runtimeDir)
                RunnerStatus.onLogLine(
                    if (ephemeral) "ephemeral: registering for the next job" else "registering runner",
                )
                RunnerRegistration.register(this, runtimeDir, config, ephemeral) { line ->
                    RunnerStatus.onLogLine(line)
                }
            }
            if (outcome.isFailure) {
                backOff(
                    "registration failed: ${outcome.exceptionOrNull()?.message}",
                    ranMillis = 0,
                    failure = AlertPolicy.Failure.REGISTRATION,
                )
                return
            }
        }
        startListener(runtimeDir)
    }

    /** Delays the next start attempt, growing the wait while failures repeat. */
    private fun backOff(reason: String, ranMillis: Long, failure: AlertPolicy.Failure) {
        if (ranMillis >= RestartPolicy.HEALTHY_RUN_MS) onHealthy()
        consecutiveFailures++
        restartDelayMs = RestartPolicy.nextDelayMs(restartDelayMs, ranMillis)
        nextStartAtMillis = System.currentTimeMillis() + restartDelayMs
        RunnerStatus.onRestarting("$reason — retrying in ${restartDelayMs / 1000}s")

        if (!AlertPolicy.shouldAlert(failure, consecutiveFailures, alerted)) return
        alerted = true
        when (failure) {
            AlertPolicy.Failure.REGISTRATION -> notifications.alert(
                "DroidRunner cannot register",
                "GitHub would not accept this device $consecutiveFailures times in a row. " +
                    "The sign-in has probably expired — open the setup screen to connect again.\n\n" +
                    reason,
            )

            AlertPolicy.Failure.LISTENER -> notifications.alert(
                "DroidRunner is not staying up",
                "The runner failed to keep running $consecutiveFailures times in a row, " +
                    "so this device is not serving jobs.\n\n" + reason,
            )
        }
    }

    /** A run long enough to count clears the failure streak and its alert. */
    private fun onHealthy() {
        consecutiveFailures = 0
        alerted = false
        notifications.clearAlert()
    }

    private fun startListener(runtimeDir: File) {
        // Keep the CLI in step with the agent API this build implements.
        DeviceCliInstaller.install(this, runtimeDir)
        val started = RunnerCommand.run(
            this, runtimeDir,
            agent?.let {
                mapOf(
                    "DROIDRUNNER_DEVICE_URL" to it.url,
                    "DROIDRUNNER_DEVICE_TOKEN_FILE" to
                        "/home/runner/${DeviceAgentServer.TOKEN_FILE_NAME}",
                )
            } ?: emptyMap(),
        ).redirectErrorStream(true).start()
        process = started
        val startedAt = System.currentTimeMillis()

        // Streaming runs off the supervisor thread so conditions keep being
        // evaluated while the listener is busy.
        thread(name = "runner-output", isDaemon = true) {
            runCatching {
                File(filesDir, "runner.log").bufferedWriter().use { log ->
                    started.inputStream.bufferedReader().forEachLine { line ->
                        log.appendLine(line)
                        log.flush()
                        RunnerStatus.onLogLine(line)
                    }
                }
            }
            val exitCode = started.waitFor()
            jobRunning.set(false)
            if (process === started) process = null
            if (stopRequested.get()) return@thread

            val ranMillis = System.currentTimeMillis() - startedAt
            if (RunnerRegistration.ephemeralEnabled(this) && exitCode == 0) {
                // Expected: an ephemeral listener exits after one job.
                restartDelayMs = 0
                nextStartAtMillis = 0
                onHealthy()
                RunnerStatus.onLogLine("ephemeral: job finished, cleaning up")
            } else {
                backOff(
                    "listener exited with code $exitCode",
                    ranMillis,
                    failure = AlertPolicy.Failure.LISTENER,
                )
            }
        }
    }

    /**
     * Stops the listener and everything proot is running for it (issue #35).
     *
     * Destroying the process we started signals proot alone, and proot both
     * survives SIGTERM and does not pass it to the runner it traces. The
     * runner then keeps its session, so a device that believes it is holding
     * jobs is still online to GitHub and can still be handed one. Signalling
     * the tree from the leaves inwards gives the runner its chance to
     * deregister first; whatever is still standing afterwards is killed.
     *
     * Returns whether the tree is actually gone.
     */
    private fun stopListener(): Boolean {
        val target = process
        jobRunning.set(false)
        process = null
        val clean = haltListenerProcesses()
        target?.destroyForcibly()
        if (!clean) {
            RunnerStatus.onLogLine(
                "warning: a listener survived being killed — GitHub may still see this runner",
            )
        }
        return clean
    }

    /**
     * Kills listeners left over from an earlier run of the app.
     *
     * When the app process dies the listener is re-parented to init and keeps
     * running, holding its GitHub session, so every later start fails with
     * "A session for this runner already exists" until the session expires.
     * Only ever called while this service owns no listener of its own.
     */
    private fun stopStrayListeners() {
        if (listenerProcesses().isEmpty()) return
        RunnerStatus.onLogLine("stopping listener processes left from an earlier run")
        haltListenerProcesses()
    }

    /**
     * Every process of this app's proot, and everything below it.
     *
     * Found by command line rather than by descending from the [Process] we
     * hold, because a listener orphaned by an app restart no longer descends
     * from anything we have a handle on — and that is exactly the one that
     * keeps a session alive.
     */
    private fun listenerProcesses(): List<Int> {
        val marker = "${applicationInfo.nativeLibraryDir}/libproot.so"
        return ProcessTree.pidsMatching(marker)
            .flatMap { ProcessTree.treeOf(it) }
            .distinct()
    }

    /** Asks the listener tree to leave, then insists. Returns true if it is gone. */
    private fun haltListenerProcesses(): Boolean {
        val tree = listenerProcesses()
        if (tree.isEmpty()) return true

        // SIGINT, not SIGTERM: only the interrupt makes the runner delete its
        // session with GitHub, and a session left behind costs the next start
        // minutes of "already exists" retries.
        tree.forEach { ProcessTree.signal(it, ProcessTree.SIGINT) }
        if (ProcessTree.awaitExit(tree, GRACEFUL_STOP_MS)) return true

        tree.filter(ProcessTree::alive).forEach { ProcessTree.signal(it, ProcessTree.SIGTERM) }
        if (ProcessTree.awaitExit(tree, FORCED_STOP_MS)) return true

        RunnerStatus.onLogLine("listener ignored the stop request; killing it")
        tree.filter(ProcessTree::alive).forEach { ProcessTree.signal(it, ProcessTree.SIGKILL) }
        return ProcessTree.awaitExit(tree, FORCED_STOP_MS)
    }

    override fun onDestroy() {
        stopRunner()
        executor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopRunner() {
        stopRequested.set(true)
        stopListener()
        RunnerStatus.setJobBoundaryListener(null)
        agent?.stop()
        agent = null
        starting.set(false)
        RunnerStatus.onServiceStopped()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "io.github.m96chan.droidrunner.STOP"
        private const val POLL_INTERVAL_MS = 5_000L

        /**
         * How long the runner gets to shut down of its own accord. A clean
         * exit is what deregisters the session with GitHub, and that is worth
         * waiting for: the alternative costs minutes of session conflicts.
         */
        private const val GRACEFUL_STOP_MS = 20_000L
        private const val FORCED_STOP_MS = 5_000L
    }
}
