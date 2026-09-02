package io.github.m96chan.droidrunner.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class CoreStat(val usage: Float, val curFreqMhz: Int)

data class SystemSnapshot(
    val cores: List<CoreStat> = emptyList(),
    val cpuAverage: Float = 0f,
    val cpuHistory: List<Float> = emptyList(),
    val memUsedBytes: Long = 0,
    val memTotalBytes: Long = 1,
    val memHistory: List<Float> = emptyList(),
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val batteryTempC: Float? = null,
    val thermalStatus: Int? = null,
    val diskUsedBytes: Long = 0,
    val diskTotalBytes: Long = 1,
    val netRxPerSec: Long = 0,
    val netTxPerSec: Long = 0,
) {
    val memFraction: Float get() = memUsedBytes.toFloat() / memTotalBytes
    val diskFraction: Float get() = diskUsedBytes.toFloat() / diskTotalBytes
}

/**
 * Polls lightweight system metrics for the dashboard. Per-core load prefers
 * /proc/stat deltas; when Android blocks that file it falls back to the current
 * scaling frequency relative to each core's maximum.
 */
class SystemMonitor(private val context: Context) {
    private val coreCount = Runtime.getRuntime().availableProcessors()
    private var previousProcStat: List<LongArray>? = null
    private var previousRxBytes = -1L
    private var previousTxBytes = -1L
    private var previousNetAtMillis = 0L
    private val cpuHistory = ArrayDeque<Float>()
    private val memHistory = ArrayDeque<Float>()

    fun snapshots(intervalMillis: Long = 1500): Flow<SystemSnapshot> = flow {
        while (true) {
            emit(sample())
            delay(intervalMillis)
        }
    }.flowOn(Dispatchers.IO)

    fun sample(): SystemSnapshot {
        val cores = readCores()
        val cpuAverage = if (cores.isEmpty()) 0f else cores.map { it.usage }.average().toFloat()

        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val memUsed = memory.totalMem - memory.availMem

        push(cpuHistory, cpuAverage)
        push(memHistory, memUsed.toFloat() / memory.totalMem)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val stats = StatFs(context.filesDir.absolutePath)
        val (rxRate, txRate) = netRates()

        return SystemSnapshot(
            cores = cores,
            cpuAverage = cpuAverage,
            cpuHistory = cpuHistory.toList(),
            memUsedBytes = memUsed,
            memTotalBytes = memory.totalMem,
            memHistory = memHistory.toList(),
            batteryPercent = battery?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level < 0 || scale <= 0) 0 else level * 100 / scale
            } ?: 0,
            charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false,
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                context.getSystemService(PowerManager::class.java).currentThermalStatus
            } else null,
            diskUsedBytes = stats.totalBytes - stats.availableBytes,
            diskTotalBytes = stats.totalBytes,
            netRxPerSec = rxRate,
            netTxPerSec = txRate,
        )
    }

    private fun push(history: ArrayDeque<Float>, value: Float) {
        history.addLast(value.coerceIn(0f, 1f))
        while (history.size > HISTORY) history.removeFirst()
    }

    private fun readCores(): List<CoreStat> {
        val fromProcStat = readProcStat()
        return List(coreCount) { core ->
            val curKhz = readLong("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            val maxKhz = readLong("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            val freqUsage = if (curKhz > 0 && maxKhz > 0) curKhz.toFloat() / maxKhz else 0f
            CoreStat(
                usage = fromProcStat?.getOrNull(core) ?: freqUsage,
                curFreqMhz = (curKhz / 1000).toInt(),
            )
        }
    }

    /** Per-core busy fraction from /proc/stat deltas, or null when unreadable. */
    private fun readProcStat(): List<Float>? {
        val lines = runCatching {
            File("/proc/stat").readLines().filter { it.matches(Regex("cpu\\d+ .*")) }
        }.getOrNull()
        if (lines.isNullOrEmpty()) return null
        val current = lines.map { line ->
            line.split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull).toLongArray()
        }
        val previous = previousProcStat
        previousProcStat = current
        if (previous == null || previous.size != current.size) return null
        return current.mapIndexed { index, now ->
            val before = previous[index]
            val total = now.sum() - before.sum()
            val idle = (now.getOrElse(3) { 0 } + now.getOrElse(4) { 0 }) -
                (before.getOrElse(3) { 0 } + before.getOrElse(4) { 0 })
            if (total <= 0) 0f else ((total - idle).toFloat() / total).coerceIn(0f, 1f)
        }
    }

    private fun netRates(): Pair<Long, Long> {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        val elapsed = now - previousNetAtMillis
        val rates = if (previousRxBytes < 0 || rx == TrafficStats.UNSUPPORTED.toLong() || elapsed <= 0) {
            0L to 0L
        } else {
            ((rx - previousRxBytes) * 1000 / elapsed).coerceAtLeast(0) to
                ((tx - previousTxBytes) * 1000 / elapsed).coerceAtLeast(0)
        }
        previousRxBytes = rx
        previousTxBytes = tx
        previousNetAtMillis = now
        return rates
    }

    private fun readLong(path: String): Long =
        runCatching { File(path).readText().trim().toLong() }.getOrDefault(-1)

    private companion object {
        const val HISTORY = 120
    }
}
