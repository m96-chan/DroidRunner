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
import io.github.m96chan.droidrunner.npu.DeviceConditionsSampler
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * The listener this service currently owns.
     *
     * Atomic, and not a plain field, for two reasons that are really one
     * (issue #212). It is written by the supervisor thread and by every
     * `runner-output` thread and read by both, so without a memory barrier a
     * dying listener's `null` may never become visible to the supervisor — it
     * would go on believing a listener exists and never start another, which
     * is a device that reads as up and serves nothing. And the revocation guard
     * has to test this handle and clear it as one step: a replacement listener
     * starting between a read and a write let the old thread null a live
     * handle, after which the next poll swept the running job's proot tree with
     * SIGKILL and took its capability token with it.
     */
    private val process = AtomicReference<Process?>(null)

    /**
     * The listener [stopListener] killed on purpose, until its output thread
     * collects it (issue #210).
     *
     * A hold — thermal, battery, storage — stops the listener the same way a
     * crash does as far as `waitFor` can tell, and it exits 130 either way.
     * Without this the output thread read every hold as a failure: the alert
     * "DroidRunner is not staying up" for a runner behaving exactly as
     * designed, and a backoff doubling toward five minutes that the device then
     * sat out *after* the charger went back in.
     */
    private val stoppedOnPurpose = AtomicReference<Process?>(null)

    /**
     * Whether a halt is already under way (issue #209). The halt runs on its
     * own thread, so a second Stop tap — which is what a user does when the
     * first appears to do nothing — must join the one in flight rather than
     * start a competing one.
     */
    private val halting = AtomicBoolean(false)

    /**
     * Set once [onDestroy] has run, so the halt thread that outlives the
     * service does not call [stopForeground] or [stopSelf] on a corpse.
     */
    @Volatile private var destroyed = false

    // Both are now touched from the halt thread as well as the main thread.
    @Volatile private var agent: DeviceAgentServer? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
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

    /**
     * Started, or started again by the system after it killed us (issue #184).
     *
     * A sticky recreation arrives with a **null intent**, which is why the stop
     * action is matched on rather than the ordinary start: anything that is not
     * an explicit stop is a start, including the one the system makes on its
     * own. Reading it the other way round would have made a recreation do
     * nothing at all, which is the failure being fixed.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            // The watchdog goes with it, for the same reason: a stop the user
            // asked for must not be undone fifteen minutes later (#184).
            RunnerWatchdog.cancel(this)
            // Returns as soon as the halt has somewhere to run (#209). This
            // method is on the main thread — `MainActivity` sends the stop with
            // `startService` — and the halt that used to run here waits out a
            // SIGINT shutdown this project measured at about nineteen seconds,
            // which is an input-dispatch ANR four times over and past the
            // twenty-second limit on a foreground service's start.
            beginStop()
            // Deliberately not sticky. A stop the user asked for must stay
            // stopped; recreating this one would make the button a no-op.
            return START_NOT_STICKY
        }
        startForeground(
            RunnerNotifications.ONGOING_ID,
            notifications.ongoing(RunnerNotifications.statusText(RunnerStatus.snapshot.value)),
        )
        // Already running: nothing to do, but still sticky, or a later kill
        // would be permanent because of a redundant start earlier.
        if (!starting.compareAndSet(false, true)) return START_STICKY
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
            conditions = { DeviceConditionsSampler.sample(this) },
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
        // Scheduled from here so it exists however the service was started —
        // by the app, by boot, or by the watchdog itself. START_STICKY is the
        // documented way back from a kill and did not work on the phone this
        // was measured on, so something outside the process has to look (#184).
        RunnerWatchdog.schedule(this)
        executor.execute { supervise(runtimeDir, mine) }
        // NOT_STICKY told Android not to bring this back after killing it, so
        // a vendor's power management taking a long-idle foreground service
        // ended the runner permanently: the listener has crash recovery, and
        // nothing watched the thing running the listener.
        //
        // Necessary but, on the phone this was measured on, not sufficient —
        // after a SIGKILL the process stayed gone and `dumpsys activity
        // services` showed no restart scheduled at all. Hence [RunnerWatchdog].
        return START_STICKY
    }

    /**
     * Starts, holds, and restarts the listener according to device conditions.
     *
     * Every pass is caught on its own (issue #211). The loop used to sit inside
     * a single `runCatching`, so the first thing to throw anywhere in the body
     * ended the supervisor for good: one `StatFs` on a path that had gone away,
     * one `registerReceiver` refused, one fork that ran out of memory while a
     * job was building, and the device stopped being a runner until the
     * fifteen-minute watchdog noticed — with the dashboard reading `Stopped`,
     * which is exactly what a stop somebody asked for looks like.
     *
     * So a throw from one pass is a bad sample and nothing more: it is logged,
     * slept off, and tried again. Only two things end the loop — an interrupt,
     * which is the service being torn down, and the [ServiceLifetime]
     * conditions that already mean "stop" — and when an error does end it, it
     * says so in a state of its own rather than borrowing the one the button
     * produces.
     */
    private fun supervise(runtimeDir: File, myGeneration: Int) {
        val configured = runCatching {
            check(RunnerRegistration.isConfigured(runtimeDir)) { "Runner is not configured" }
        }
        if (configured.isFailure) {
            // Not a bad moment: nothing this loop could do would make an
            // unconfigured device configured, so retrying would only fill the
            // log with the same line every five seconds.
            finish(myGeneration, configured.exceptionOrNull()?.message ?: "the runner is not configured")
            return
        }

        var admissionState = AdmissionPolicy.State()
        var reportedFor: String? = null
        var held = false
        // What the last pass failed with, and how many passes have failed the
        // same way since, so a permanent fault says so once rather than ninety
        // times an hour (#211, and the lesson of #214).
        var lastError: String? = null
        var repeats = 0

        while (ServiceLifetime.shouldKeepRunning(myGeneration, generation.get(), stopRequested.get())) {
            val pass = runCatching {
                val thresholds = AdmissionThresholds.load(this)
                val evaluation = AdmissionPolicy.evaluate(sampleConditions(), thresholds, admissionState)
                admissionState = evaluation.state
                val step = SupervisorStep.decide(
                    decision = evaluation.admission,
                    hasProcess = process.get() != null,
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
                if (releaseHeldSessionIfStuck(runtimeDir)) return@runCatching

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

            val error = pass.exceptionOrNull()
            if (error == null) {
                lastError = null
                repeats = 0
                continue
            }
            if (!ServiceLifetime.survivesIterationError(error)) {
                // An interrupt is `executor.shutdownNow()`, which only happens
                // when the service is going away. Restore the flag and leave
                // quietly: nobody needs telling that a stop stopped something.
                Thread.currentThread().interrupt()
                break
            }
            val message = error.message ?: error::class.java.simpleName
            repeats = if (message == lastError) repeats + 1 else 0
            if (ServiceLifetime.shouldReportIterationError(lastError, message, repeats)) {
                RunnerStatus.onAppLine("runner: recovering from an error in the supervisor — $message")
            }
            lastError = message
            // Same wait as a good pass, so a device that is failing to sample
            // its own conditions still notices the moment it stops failing.
            if (runCatching { Thread.sleep(POLL_INTERVAL_MS) }.exceptionOrNull() != null) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Whatever ended the loop here was asked for: a stop, a newer start, or
        // the service being torn down. Nothing to explain.
        finish(myGeneration, null)
    }

    /**
     * Hands the service back once the supervisor has finished.
     *
     * A superseded supervisor leaves everything alone: `starting` and the
     * service now belong to whoever replaced it. [failure] is the reason the
     * supervisor stopped when nobody asked it to, which is published rather
     * than logged so the dashboard can tell it apart from the button (#211).
     */
    private fun finish(myGeneration: Int, failure: String?) {
        if (generation.get() != myGeneration) return
        starting.set(false)
        if (failure != null) RunnerStatus.onSupervisorFailed(failure)
        if (ServiceLifetime.shouldStopService(myGeneration, generation.get(), stopRequested.get())) {
            stopSelf(latestStartId)
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
        // The halt now runs on a thread of its own and takes about nineteen
        // seconds, so for the first time there is a window in which a stop is
        // under way and the supervisor has not left yet. Starting a listener
        // into that window would hand the halt a process it never saw and leave
        // it orphaned, holding its GitHub session (#209).
        if (stopRequested.get()) return
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
        // Same window as in [launchListener]: registration can take a while,
        // and a stop may have arrived during it (#209).
        if (stopRequested.get()) return
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
        process.set(started)
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
            // A listener that dies mid-job never prints its completion line, so
            // the log-parsing path that normally ends a job never fires and the
            // capability token stayed valid for as long as the service lived
            // (#172). Revoked here, where the process is known to be gone.
            //
            // Only when this thread's listener is still the current one, and
            // the test and the clear are one step (#212). A later listener may
            // already have started a job and been issued a fresh token; an
            // older thread that read "still current", was overtaken, and then
            // wrote null would have cut off the job that is actually running
            // and left the supervisor believing nothing was.
            if (TokenRevocation.revokeIfCurrent(process, started)) {
                agent?.onJobActive(false)
            }

            val ranMillis = System.currentTimeMillis() - startedAt
            // Who ended this listener, and therefore whether its exit says
            // anything about the health of this device (#210). A hold is the
            // supervisor doing its job, not the runner failing to do its.
            val exit = ListenerExit.classify(
                stopRequested = stopRequested.get(),
                stoppedOnPurpose = stoppedOnPurpose.compareAndSet(started, null),
                ephemeral = RunnerRegistration.ephemeralEnabled(this),
                exitCode = exitCode,
            )
            if (exit == ListenerExit.Kind.SERVICE_STOP) return@thread
            if (ListenerExit.clearsBackoff(exit)) {
                // The next start is due the moment the supervisor is willing to
                // make one. Leaving the backoff where it was is what kept a
                // phone idle for up to five minutes after the charger went back
                // in, because `SupervisorStep` gates `Start` on this.
                restartDelayMs = 0
                nextStartAtMillis = 0
            }
            when (exit) {
                ListenerExit.Kind.POLICY_STOP -> RunnerStatus.onAppLine(
                    "admission: the listener stopped as asked (exit $exitCode), " +
                        "which is not a failure",
                )

                ListenerExit.Kind.JOB_COMPLETED -> {
                    // Expected: an ephemeral listener exits after one job.
                    onHealthy()
                    RunnerStatus.onAppLine("ephemeral: job finished, cleaning up")
                }

                ListenerExit.Kind.FAILURE -> backOff(
                    "listener exited with code $exitCode",
                    ranMillis,
                    failure = AlertPolicy.Failure.LISTENER,
                )

                ListenerExit.Kind.SERVICE_STOP -> Unit
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
     *
     * Never call this from the main thread: the wait below is measured in tens
     * of seconds (#209).
     */
    private fun stopListener(): Boolean {
        val target = process.getAndSet(null)
        jobRunning.set(false)
        // Remembered so the output thread, which is about to wake up with an
        // exit code that looks exactly like a crash, can tell that this one was
        // asked for (#210). Written before the signals go out, so it is there
        // however quickly the listener dies.
        stoppedOnPurpose.set(target)
        // Stopping is deliberate, so there is no newer job to protect: revoke
        // unconditionally rather than waiting for a completion line that is
        // never coming (#172).
        agent?.onJobActive(false)
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
        // Also off the main thread (#209): `onDestroy` runs there too, so the
        // system reclaiming this service used to spend the same nineteen
        // seconds blocking the UI as the Stop button did.
        //
        // Marked first: the thread the halt runs on outlives this object, and
        // a service that has been destroyed has nothing left to un-foreground.
        destroyed = true
        beginStop()
        executor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Begins the halt, and returns (issue #209).
     *
     * Everything cheap happens here, on the caller's thread, so that a stop is
     * immediately true and immediately visible: the supervisor is told to leave
     * at its next poll, the dashboard and the notification get a *stopping*
     * state to show, and `starting` is released so a start arriving during the
     * halt is a real start rather than a no-op. Everything slow — signalling
     * the proot tree and waiting for it — happens on a thread of its own.
     *
     * The service is taken down by that thread once the tree is confirmed gone,
     * not here, which is also what stops it being destroyed halfway through its
     * own shutdown.
     */
    private fun beginStop() {
        stopRequested.set(true)
        starting.set(false)
        RunnerStatus.onStopping()
        // Captured now: by the time the halt ends, a later start may have
        // claimed the service, and neither its start id nor its supervisor is
        // this stop's to cancel (#68).
        val stopId = latestStartId
        val atGeneration = generation.get()
        ServiceLifetime.beginStop(
            halting = halting,
            onThread = { body -> thread(name = "runner-stop") { body.run() } },
            halt = { stopRunner(stopId, atGeneration) },
        )
    }

    /** The slow half of a stop. Runs on the `runner-stop` thread only. */
    private fun stopRunner(startId: Int, atGeneration: Int) {
        stopRequested.set(true)
        val clean = stopListener()
        if (generation.get() != atGeneration) {
            // A start got in while the tree was coming down. It has its own
            // agent, wake lock and supervisor, and switching those off here
            // would leave it running nothing at all — the #68 failure, reached
            // by a different route now that the halt outlives the call.
            RunnerStatus.onAppLine(
                "stop: a start arrived while the listener was shutting down; leaving it running",
            )
            return
        }
        RunnerStatus.setJobBoundaryListener(null)
        agent?.stop()
        agent = null
        RunnerStatus.onServiceStopped()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (!clean) {
            RunnerStatus.onAppLine("stop: giving up the notification with a listener still unaccounted for")
        }
        // Only now, with the tree confirmed gone. Doing this first is what let
        // the system tear the service down in the middle of its own halt.
        if (destroyed) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        // With the id, Android keeps the service alive if a start arrived after
        // this stop — a bare stopSelf() would take that start down with it and
        // leave the device looking idle.
        stopSelf(startId)
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
    private fun qnnModelRunner(): (
        (java.io.File, String, Int, List<java.io.File>, io.github.m96chan.droidrunner.npu.TensorIo.Target?, Boolean) -> String
    )? {
        val htpVersion = HexagonVersion.of(DeviceCapabilities.detect().soc) ?: return null
        val installer = QnnInstaller(this)
        return { model, backend, iterations, inputs, outputTarget, keepTimings ->
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
                    keepTimings = keepTimings,
                )
            }
        }
    }

}
