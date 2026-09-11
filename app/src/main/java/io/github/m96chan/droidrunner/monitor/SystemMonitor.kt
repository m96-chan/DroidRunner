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

/** Where a core's meter reading came from. These are not the same measurement. */
enum class CoreUsageSource {
    /** Busy fraction between two /proc/stat samples of this same core. */
    PROC_STAT,

    /** scaling_cur_freq over cpuinfo_max_freq: clock headroom, not busy time. */
    FREQUENCY,

    /** Nothing to report for this core in this sample. */
    NONE,
}

/**
 * One core's row in the cpu panel.
 *
 * [index] is the cpu's own number — cpu3 is `index = 3` whether or not cpu2 is
 * parked — and not its position in the list, because a position is exactly
 * what stopped meaning anything in issue #195.
 *
 * [usage] is null when there is no utilisation to report: /proc/stat lists
 * only the CPUs that are online, so a core parked for either of the two
 * samples a delta is taken across has no delta at all. Null is not zero — the
 * whole point is that a parked core must not be drawn as an idle one, which is
 * what reading a neighbour's counters used to make it.
 */
data class CoreStat(
    val index: Int,
    val usage: Float?,
    val curFreqMhz: Int,
    val source: CoreUsageSource = CoreUsageSource.NONE,
)

data class SystemSnapshot(
    val cores: List<CoreStat> = emptyList(),
    /**
     * Mean load over the cores that were actually measured, or null when none
     * were (issue #239).
     *
     * Nullable for the same reason [CoreStat.usage] is. `0f` is a load, and on
     * a phone where `/proc/stat` cannot be read — every phone in this fleet
     * running Android 16 — *no* core is ever measured, so the meter sat at a
     * solid `0%` and read as an idle CPU on a device that had just finished a
     * job. Unmeasured has to be sayable.
     */
    val cpuAverage: Float? = null,
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
 * Polls lightweight system metrics for the dashboard.
 *
 * Per-core load is the busy fraction between two /proc/stat samples of the
 * same core. When that file cannot be read at all, each core falls back to its
 * scaling frequency over its maximum — a different measurement, reported as
 * such ([CoreUsageSource.FREQUENCY]) and kept out of the average and the
 * history graph, which carry measured utilisation only (issue #195).
 */
class SystemMonitor(private val context: Context) {
    private val cpu = ProcStatCpuSampler()

    /**
     * The cores this device has, from /sys/devices/system/cpu/present.
     *
     * Not availableProcessors(): that counts the cores online at the moment it
     * is asked, and asking once at construction left the panel a core short
     * for the life of the process on a phone started with one parked. Read
     * lazily and cached only on success, since `present` describes what the
     * hardware has and does not change while the phone is on.
     */
    private var present: List<Int>? = null
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
        // Measured cores only. Folding in a frequency ratio, or a zero stood in
        // for a core with nothing to report, would put two different metrics on
        // one line (issue #195) — and this is the number the graph plots.
        val measured = cores.mapNotNull { core ->
            core.usage.takeIf { core.source == CoreUsageSource.PROC_STAT }
        }
        val cpuAverage = if (measured.isEmpty()) null else measured.average().toFloat()

        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val memUsed = memory.totalMem - memory.availMem

        // Nothing measured is not a measurement of nothing: the first poll
        // after start has no previous /proc/stat to subtract from, and a 0%
        // notch drawn there is a load the phone never had.
        cpuAverage?.let { push(cpuHistory, it) }
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
            // EXTRA_PLUGGED and deliberately not `status == CHARGING`: a full
            // battery reports BATTERY_STATUS_FULL while still on the cable, and
            // reading that as "not charging" would put every device in the fleet
            // on hold at 100% — with admission control naming a reason the owner
            // can see is false, since the phone is plainly plugged in.
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
        val body = runCatching { File(PROC_STAT).readText() }.getOrNull()
        // An unreadable file is fed through as an empty one on purpose: it
        // clears the baseline, so if the file comes back the next reading is
        // not a delta across however long it was gone for.
        val usages = cpu.sample(body.orEmpty())
        return coreIndices(usages.keys).map { core ->
            val curKhz = readLong("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            val maxKhz = readLong("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            val mhz = if (curKhz > 0) (curKhz / 1000).toInt() else 0
            val measured = usages[core]
            when {
                measured != null -> CoreStat(core, measured, mhz, CoreUsageSource.PROC_STAT)
                // Only when the file itself is unreadable. A core missing from
                // a readable /proc/stat is parked, and a parked core's cpufreq
                // nodes are unreadable too, so there is nothing to stand in
                // with — and a neighbour's counters are not it.
                body == null && curKhz > 0 && maxKhz > 0 ->
                    CoreStat(core, curKhz.toFloat() / maxKhz, mhz, CoreUsageSource.FREQUENCY)
                else -> CoreStat(core, null, mhz, CoreUsageSource.NONE)
            }
        }
    }

    /**
     * Which cores to draw: every core the hardware has, plus any index seen in
     * /proc/stat that `present` did not account for, so a core can never go
     * missing from the panel and the row for cpuN is always the row for cpuN.
     */
    private fun coreIndices(seen: Set<Int>): List<Int> {
        val known = present ?: parseCpuList(runCatching { File(PRESENT).readText() }.getOrNull())
            .takeIf { it.isNotEmpty() }
            ?.also { present = it }
            ?: (0 until Runtime.getRuntime().availableProcessors()).toList()
        return (known + seen).distinct().sorted()
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
        const val PROC_STAT = "/proc/stat"
        const val PRESENT = "/sys/devices/system/cpu/present"
    }
}

/**
 * Per-core busy fractions from successive /proc/stat bodies, keyed by the
 * number in the `cpuN` label (issue #195).
 *
 * The label is the whole point. /proc/stat lists only the CPUs that are
 * online, and every phone in the fleet runs core control, so the rows move
 * under the reader: park cpu4 while cpu7 unparks and the row count does not
 * change, but the fifth row is now cpu5. Subtracting position from position
 * then measured cpu5 against cpu4 — a negative delta, drawn as an idle core
 * while it was fully loaded.
 *
 * A core is therefore only ever compared against itself, and a core that was
 * not online for both samples is simply absent from the result. That is what
 * an offline core has: no utilisation, which is not the same as no load.
 *
 * Stateful, so it is a class rather than a function, and it takes the file
 * body rather than reading it — the whole behaviour is two bodies in sequence,
 * and that is how the test drives it.
 */
internal class ProcStatCpuSampler {
    private var previous: Map<Int, LongArray> = emptyMap()

    /** Busy fraction per cpu index, for the cores measurable this time. */
    fun sample(procStat: String): Map<Int, Float> {
        val current = parse(procStat)
        val before = previous
        previous = current
        return buildMap {
            current.forEach { (index, now) ->
                // Absent from the previous body: offline then, or this is the
                // first sample. Either way there is no interval to divide by.
                val was = before[index] ?: return@forEach
                val total = now.sum() - was.sum()
                // Counters that did not advance, or went backwards because the
                // core was hotplugged between the two reads, describe no
                // interval either. Reporting 0f here is what used to make a
                // busy core look idle.
                if (total <= 0) return@forEach
                val idle = (now.idle() - was.idle()).coerceIn(0, total)
                put(index, ((total - idle).toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /** idle + iowait, fields 4 and 5 of the cpu line. */
    private fun LongArray.idle(): Long = getOrElse(3) { 0 } + getOrElse(4) { 0 }

    private fun parse(procStat: String): Map<Int, LongArray> = buildMap {
        procStat.lineSequence().forEach { line ->
            // The aggregate `cpu ` line carries no number and is not a core.
            val match = CPU_LINE.matchEntire(line.trim()) ?: return@forEach
            val index = match.groupValues[1].toIntOrNull() ?: return@forEach
            put(
                index,
                match.groupValues[2].split(FIELDS).mapNotNull(String::toLongOrNull).toLongArray(),
            )
        }
    }

    private companion object {
        val CPU_LINE = Regex("""cpu(\d+)\s+(.*)""")
        val FIELDS = Regex("""\s+""")
    }
}

/**
 * The cpu indices in a sysfs cpulist such as `0-7` or `0-3,5,7`.
 *
 * Empty when the file could not be read or says something unexpected, which
 * the caller takes as "ask the runtime instead" rather than "this phone has no
 * cores".
 */
internal fun parseCpuList(text: String?): List<Int> =
    text.orEmpty().trim().split(',').flatMap { part ->
        val bounds = part.split('-')
        val from = bounds.firstOrNull()?.toIntOrNull()
        val to = bounds.lastOrNull()?.toIntOrNull()
        if (from == null || to == null || bounds.size > 2 || from > to) {
            emptyList()
        } else {
            (from..to).toList()
        }
    }.distinct().sorted()
