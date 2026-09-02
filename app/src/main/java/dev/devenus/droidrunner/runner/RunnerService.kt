package dev.devenus.droidrunner.runner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.devenus.droidrunner.monitor.SystemMonitor
import dev.devenus.droidrunner.npu.DeviceAgentServer
import dev.devenus.droidrunner.npu.DeviceCapabilitiesJson
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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

    /** Restart backoff state, owned by the supervisor and the output thread. */
    @Volatile private var restartDelayMs = 0L
    @Volatile private var nextStartAtMillis = 0L

    override fun onCreate() {
        super.onCreate()
        RunnerStatus.attach(this)
        monitor = SystemMonitor(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "GitHub Runner", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRunner()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification("Waiting for GitHub Actions jobs"))
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
                            updateNotification("Paused: ${decision.reason}")
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
                backOff("missing stored registration details", ranMillis = 0)
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
                backOff("registration failed: ${outcome.exceptionOrNull()?.message}", ranMillis = 0)
                return
            }
        }
        startListener(runtimeDir)
    }

    /** Delays the next start attempt, growing the wait while failures repeat. */
    private fun backOff(reason: String, ranMillis: Long) {
        restartDelayMs = RestartPolicy.nextDelayMs(restartDelayMs, ranMillis)
        nextStartAtMillis = System.currentTimeMillis() + restartDelayMs
        RunnerStatus.onRestarting("$reason — retrying in ${restartDelayMs / 1000}s")
    }

    private fun startListener(runtimeDir: File) {
        updateNotification("Waiting for GitHub Actions jobs")
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
                RunnerStatus.onLogLine("ephemeral: job finished, cleaning up")
            } else {
                backOff("listener exited with code $exitCode", ranMillis)
            }
        }
    }

    /** SIGTERM and wait, so the listener deregisters its session with GitHub. */
    private fun stopListener() {
        process?.let {
            it.destroy()
            runCatching { it.waitFor(LISTENER_STOP_TIMEOUT_S, TimeUnit.SECONDS) }
        }
        process = null
        jobRunning.set(false)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("DroidRunner")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
        }
    }

    override fun onDestroy() {
        stopRunner()
        executor.shutdownNow()
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
        const val ACTION_STOP = "dev.devenus.droidrunner.STOP"
        private const val CHANNEL = "runner"
        private const val NOTIFICATION = 96
        private const val POLL_INTERVAL_MS = 5_000L
        private const val LISTENER_STOP_TIMEOUT_S = 5L
    }
}
