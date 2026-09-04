package io.github.m96chan.droidrunner.runner

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.github.GitHubApi
import io.github.m96chan.droidrunner.github.SignInExpiredException
import io.github.m96chan.droidrunner.github.UserSession
import io.github.m96chan.droidrunner.npu.NpuLabels
import io.github.m96chan.droidrunner.security.SecretStore
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
import io.github.m96chan.droidrunner.device.HexagonVersion
import io.github.m96chan.droidrunner.npu.QnnClient
import io.github.m96chan.droidrunner.npu.QnnVerificationStore
import io.github.m96chan.droidrunner.npu.QnnInstaller
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

    /**
     * Which run of the service owns it (issue #68). A start bumps this; a
     * supervisor compares it before touching anything shared, so one on its
     * way out cannot switch off a service a newer start has claimed.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /** The most recent start, so a stop cannot cancel one that arrived after it. */
    @Volatile private var latestStartId = 0

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
        latestStartId = startId
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
        // Timestamp this boot while the service is coming up: a device that was
        // held at the lock screen only finds out here how long it was away.
        RunnerStatus.recordBootStart(this)

        // Device Agent: loopback bridge that jobs use to reach Android-side
        // hardware (NNAPI); URL and token file are injected into the runner
        // environment, which jobs inherit.
        val runtimeDir = RuntimeInstaller(this).runtimeDir
        agent = DeviceAgentServer(
            runtimeDir = runtimeDir,
            capabilitiesJson = { DeviceCapabilitiesJson.build(this) },
            qnnModel = qnnModelRunner(),
        ).also { server ->
            server.start()
            RunnerStatus.setJobBoundaryListener { active ->
                jobRunning.set(active)
                // Fresh capability token per job; revoked when the job ends.
                server.onJobActive(active)
            }
        }

        val mine = generation.incrementAndGet()
        scope.launch { reconcileLabels(runtimeDir) }
        executor.execute { supervise(runtimeDir, mine) }
        return START_NOT_STICKY
    }

    /** Starts, holds, and restarts the listener according to device conditions. */
    private fun supervise(runtimeDir: File, myGeneration: Int) {
        runCatching {
            check(RunnerRegistration.isConfigured(runtimeDir)) { "Runner is not configured" }
            var admissionState = AdmissionPolicy.State()
            var reportedFor: String? = null
            var held = false

            while (ServiceLifetime.shouldKeepRunning(myGeneration, generation.get(), stopRequested.get())) {
                val thresholds = AdmissionThresholds.load(this)
                val evaluation = AdmissionPolicy.evaluate(sampleConditions(), thresholds, admissionState)
                admissionState = evaluation.state
                val step = SupervisorStep.decide(
                    decision = evaluation.admission,
                    hasProcess = process != null,
                    jobRunning = jobRunning.get(),
                    nowMillis = System.currentTimeMillis(),
                    nextStartAtMillis = nextStartAtMillis,
                    reportedFor = reportedFor,
                    held = held,
                )
                reportedFor = step.reportedFor
                held = step.held

                // An app update leaves GitHub holding the old session, and the
                // replacement listener can spend minutes being refused. The
                // entry that owns that session is the one re-registering
                // replaces, so once the wait has outlasted its welcome, end it
                // rather than sit through it (issue #79).
                if (releaseHeldSessionIfStuck(runtimeDir)) continue

                step.actions.forEach { action ->
                    when (action) {
                        SupervisorStep.Action.Resume -> {
                            RunnerStatus.onResumed()
                            RunnerStatus.onAppLine("admission: conditions recovered, resuming")
                        }

                        SupervisorStep.Action.ClearCondition -> RunnerStatus.onConditionRecovered()
                        is SupervisorStep.Action.ReportCondition ->
                            RunnerStatus.onConditionObserved(action.reason)

                        SupervisorStep.Action.SweepStrays -> stopStrayListeners()
                        SupervisorStep.Action.Start -> launchListener(runtimeDir)
                        is SupervisorStep.Action.Stop -> {
                            RunnerStatus.onAppLine(
                                "admission: ${action.reason}" +
                                    if (action.stopsActiveJob) " — stopping active job" else "",
                            )
                            stopListener()
                        }

                        is SupervisorStep.Action.AnnounceHold -> {
                            RunnerStatus.onPaused(action.reason)
                        }
                    }
                }
                // Short sleep so a due restart is honoured promptly; the
                // condition sampling itself is cheap.
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }.onFailure {
            if (it !is InterruptedException) RunnerStatus.onAppLine("runner error: ${it.message}")
        }
        // A superseded supervisor leaves both alone: `starting` and the
        // service now belong to whoever replaced it.
        if (generation.get() == myGeneration) {
            starting.set(false)
            if (ServiceLifetime.shouldStopService(myGeneration, generation.get(), stopRequested.get())) {
                stopSelf(latestStartId)
            }
        }
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
                RunnerStatus.onAppLine(
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
                RunnerStatus.onAppLine(
                    if (ephemeral) "ephemeral: registering for the next job" else "registering runner",
                )
                RunnerRegistration.register(this, runtimeDir, config, ephemeral) { line ->
                    RunnerStatus.onRunnerLine(line)
                }
            }
            if (outcome.isFailure) {
                val cause = outcome.exceptionOrNull()
                backOff(
                    "registration failed: ${cause?.message}",
                    ranMillis = 0,
                    // A refused renewal is a real sign-out, not a bad moment:
                    // it says so at once instead of waiting out a streak.
                    failure = if (cause is SignInExpiredException) {
                        AlertPolicy.Failure.SIGN_IN
                    } else {
                        AlertPolicy.Failure.REGISTRATION
                    },
                )
                return
            }
        }
        startListener(runtimeDir)
    }

    /**
     * Replaces the runner when GitHub is still holding a session the previous
     * process never got to release.
     *
     * Returns true when it acted, so the caller starts the loop again against
     * a listener that no longer exists.
     *
     * Deliberately conservative. It waits out [SessionConflict.PATIENCE_MS]
     * first, because the listener usually gets through on its own and a
     * needless re-registration costs a registration token and a new runner id.
     * It does nothing at all without a user sign-in — a hand-entered PAT is
     * not assumed to carry the scope, the same stance [reconcileLabels] takes
     * — and nothing while a job is running, which would be a worse cure than
     * the disease.
     */
    private fun releaseHeldSessionIfStuck(runtimeDir: File): Boolean {
        val heldSince = RunnerStatus.snapshot.value.sessionHeldSince ?: return false
        if (!SessionConflict.shouldReplaceRunner(heldSince, System.currentTimeMillis())) return false
        if (jobRunning.get()) return false

        val config = RunnerRegistration.load(runtimeDir) ?: return false
        val token = UserSession(SecretStore(this), BuildConfig.GITHUB_APP_CLIENT_ID).accessToken()
        if (token == null) {
            RunnerStatus.onAppLine(
                "session: GitHub still holds the previous session; sign in to replace the " +
                    "runner rather than wait it out",
            )
            // Said once. Clearing the marker stops this repeating every poll,
            // and the listener is still retrying underneath.
            RunnerStatus.onSessionWaitAcknowledged()
            return false
        }

        RunnerStatus.onAppLine("session: still held after a minute — replacing the runner")
        stopListener()
        val replaced = runCatching {
            RunnerRegistration.register(
                this,
                runtimeDir,
                config,
                RunnerRegistration.ephemeralEnabled(this),
            ) { line -> RunnerStatus.onRunnerLine(line) }
        }
        RunnerStatus.onSessionWaitAcknowledged()
        if (replaced.isFailure) {
            RunnerStatus.onAppLine(
                "session: could not replace the runner (${replaced.exceptionOrNull()?.message})",
            )
        }
        return true
    }

    /** Delays the next start attempt, growing the wait while failures repeat. */
    private fun backOff(reason: String, ranMillis: Long, failure: AlertPolicy.Failure) {
        if (ranMillis >= RestartPolicy.HEALTHY_RUN_MS) onHealthy()
        val failureRecord = AlertPolicy.recordFailure(failure, consecutiveFailures, alerted)
        consecutiveFailures = failureRecord.consecutiveFailures
        alerted = failureRecord.alerted
        restartDelayMs = RestartPolicy.nextDelayMs(restartDelayMs, ranMillis)
        nextStartAtMillis = System.currentTimeMillis() + restartDelayMs
        RunnerStatus.onRestarting("$reason — retrying in ${restartDelayMs / 1000}s")

        if (!failureRecord.alertNow) return
        when (failure) {
            AlertPolicy.Failure.REGISTRATION -> notifications.alert(
                "DroidRunner cannot register",
                "GitHub would not accept this device $consecutiveFailures times in a row. " +
                    "The sign-in has probably expired — open the setup screen to connect again.\n\n" +
                    reason,
            )

            AlertPolicy.Failure.SIGN_IN -> notifications.alert(
                "DroidRunner is signed out",
                "GitHub would not renew this device's sign-in, so it can no longer " +
                    "register — open the setup screen to connect again.\n\n" + reason,
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
        // Marked from here, not from the output thread: the header then lands
        // after the app lines that led to this start, and before the first
        // thing the listener says.
        RunnerStatus.onListenerAttempt(startedAt)

        // Streaming runs off the supervisor thread so conditions keep being
        // evaluated while the listener is busy.
        thread(name = "runner-output", isDaemon = true) {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    RunnerStatus.onRunnerLine(line)
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
                RunnerStatus.onAppLine("ephemeral: job finished, cleaning up")
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
            RunnerStatus.onAppLine(
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
        RunnerStatus.onAppLine("stopping listener processes left from an earlier run")
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

    /**
     * Corrects what this device tells GitHub about itself (issue #80).
     *
     * Labels are written once, at registration, so a device goes on announcing
     * what the app believed when it was set up — one phone in the fleet is a
     * Snapdragon 8 Gen 3 still labelled `android-no-npu` from before the SoC
     * matching was fixed. GitHub can replace a runner's labels without giving
     * it a new identity, so this reconciles rather than re-registers and the
     * listener is never interrupted.
     *
     * Best effort by design. No token, no network, or a runner GitHub does not
     * know about is not worth stopping a device that is otherwise working.
     */
    private fun reconcileLabels(runtimeDir: File) {
        runCatching {
            val config = RunnerRegistration.load(runtimeDir) ?: return
            // Only a user sign-in can rewrite labels; a hand-entered PAT is
            // left alone rather than assumed to carry the right scope.
            val token = UserSession(SecretStore(this), BuildConfig.GITHUB_APP_CLIENT_ID)
                .accessToken() ?: return
            val current = DeviceCapabilities.detect().labels() +
                NpuLabels.cached(this) +
                // Only where it was earned: a model shown to run on the
                // Hexagon, not a SoC name that looks like one (#80, #82).
                QnnVerificationStore(this).labels()
            val api = GitHubApi()
            val registered = api.runnerLabels(config.target, config.runnerName, token)
            if (registered.isEmpty()) return
            if (!LabelReconciliation.needsUpdate(current, registered)) return

            val id = api.runnerId(config.target, config.runnerName, token) ?: return
            api.replaceLabels(config.target, id, LabelReconciliation.payload(current), token)
            RunnerStatus.onAppLine("labels: updated to match this device")
        }
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

        RunnerStatus.onAppLine("listener ignored the stop request; killing it")
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
        // With the id, Android keeps the service alive if a start arrived after
        // this stop — a bare stopSelf() would take that start down with it and
        // leave the device looking idle.
        stopSelf(latestStartId)
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

    /**
     * Hands jobs the Qualcomm accelerator, or null on a device that has none
     * or has not installed the runtime.
     *
     * Resolved per request rather than once at start: someone can accept the
     * licences and install the runtime while the runner is up, and should not
     * have to restart it to use what they just installed (issue #82).
     */
    private fun qnnModelRunner(): ((java.io.File, String, Int, List<java.io.File>, io.github.m96chan.droidrunner.npu.TensorIo.Target?) -> String)? {
        val htpVersion = HexagonVersion.of(DeviceCapabilities.detect().soc) ?: return null
        val installer = QnnInstaller(this)
        return { model, backend, iterations, inputs, outputTarget ->
            if (installer.installed == null) {
                org.json.JSONObject()
                    .put("ok", false)
                    .put(
                        "error",
                        "the Qualcomm NPU runtime is not installed on this device; " +
                            "accept the licences in setup to install it",
                    )
                    .toString()
            } else {
                QnnClient(this).runModel(
                    installDir = installer.installDir,
                    htpVersion = htpVersion,
                    model = model,
                    backend = backend,
                    iterations = iterations,
                    inputs = inputs,
                    outputTarget = outputTarget,
                )
            }
        }
    }

}
