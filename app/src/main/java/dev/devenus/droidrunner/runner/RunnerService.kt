package dev.devenus.droidrunner.runner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.devenus.droidrunner.npu.DeviceAgentServer
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RunnerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var agent: DeviceAgentServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val starting = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        RunnerStatus.attach(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub Runner", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRunner()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DroidRunner")
            .setContentText("Waiting for GitHub Actions jobs")
            .setOngoing(true).build())
        if (!starting.compareAndSet(false, true)) return START_NOT_STICKY
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidRunner:Runner").also { it.acquire() }
        RunnerStatus.onServiceStarted()
        // Device Agent: loopback bridge that jobs use to reach Android-side
        // hardware (NNAPI); URL and capability token are injected into the
        // runner environment, which jobs inherit.
        agent = DeviceAgentServer(this).also { it.start() }
        executor.execute {
            val runtime = RuntimeInstaller(this).runtimeDir
            runCatching {
                check(File(runtime, ".configured").isFile) { "Runner is not configured" }
                val started = RunnerCommand.run(
                    this, runtime,
                    agent?.let {
                        mapOf(
                            "DROIDRUNNER_DEVICE_URL" to it.url,
                            "DROIDRUNNER_DEVICE_TOKEN" to it.token,
                        )
                    } ?: emptyMap(),
                )
                    .redirectErrorStream(true)
                    .start()
                process = started
                File(filesDir, "runner.log").bufferedWriter().use { log ->
                    started.inputStream.bufferedReader().forEachLine { line ->
                        log.appendLine(line)
                        RunnerStatus.onLogLine(line)
                    }
                }
                started.waitFor()
            }.onFailure { RunnerStatus.onLogLine("runner error: ${it.message}") }
            starting.set(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRunner()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopRunner() {
        // SIGTERM first and give the listener a moment to deregister its
        // session with GitHub; otherwise the next start hits
        // "a session for this runner already exists".
        process?.let {
            it.destroy()
            runCatching { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
        process = null
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
    }
}
