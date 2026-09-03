package io.github.m96chan.droidrunner.runner

import java.io.File

/**
 * Reads and signals the processes the app started (issue #35).
 *
 * `Process.destroy()` signals the direct child, which is proot — and proot both
 * survives SIGTERM and does not pass it to the runner it is tracing. Stopping
 * the listener therefore means finding the whole tree and signalling it from
 * the leaves inwards, so the runner has its chance to deregister before proot
 * goes away underneath it.
 *
 * Android mounts `/proc` with `hidepid`, so a scan only ever sees this app's
 * own processes.
 */
object ProcessTree {
    const val SIGTERM = 15
    const val SIGKILL = 9

    /**
     * The `ppid` from a `/proc/<pid>/stat` line.
     *
     * The second field is the executable name in parentheses and may itself
     * contain spaces and parentheses, so the fields are read from the last
     * `)` rather than by splitting the whole line.
     */
    fun ppidOf(stat: String): Int? {
        val afterComm = stat.substringAfterLast(')', missingDelimiterValue = "")
        val fields = afterComm.trim().split(' ')
        // state, ppid, ...
        return fields.getOrNull(1)?.toIntOrNull()
    }

    /**
     * [root] and everything descending from it, **children before parents**, so
     * a caller signalling in order never orphans a process by killing its
     * parent first.
     */
    fun descendantsOf(parents: Map<Int, Int>, root: Int): List<Int> {
        val childrenOf = parents.entries.groupBy({ it.value }, { it.key })
        val ordered = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        fun walk(pid: Int) {
            if (!seen.add(pid)) return // a /proc snapshot can disagree with itself
            childrenOf[pid].orEmpty().forEach(::walk)
            ordered += pid
        }
        walk(root)
        return ordered
    }

    /** Every live pid mapped to its parent, from `/proc`. */
    fun snapshotParents(): Map<Int, Int> = buildMap {
        File("/proc").listFiles().orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            ppidOf(stat)?.let { put(pid, it) }
        }
    }

    fun treeOf(rootPid: Int): List<Int> = descendantsOf(snapshotParents(), rootPid)

    fun alive(pid: Int): Boolean = File("/proc/$pid").exists()

    /**
     * Live pids whose command line mentions [marker] — used to find listeners
     * left behind by a previous run of the app, which `init` has re-parented
     * and which no longer descend from anything we hold.
     */
    fun pidsMatching(marker: String): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val cmdline = runCatching { File(entry, "cmdline").readText() }.getOrNull()
                ?: return@mapNotNull null
            pid.takeIf { cmdline.contains(marker) }
        }

    fun signal(pid: Int, signal: Int) {
        runCatching { android.os.Process.sendSignal(pid, signal) }
    }

    /** Blocks until none of [pids] is alive, or [timeoutMs] passes. */
    fun awaitExit(pids: List<Int>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pids.none(::alive)) return true
            Thread.sleep(POLL_MS)
        }
        return pids.none(::alive)
    }

    private const val POLL_MS = 200L
}
