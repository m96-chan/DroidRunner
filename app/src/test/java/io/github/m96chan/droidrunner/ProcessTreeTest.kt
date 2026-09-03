package io.github.m96chan.droidrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessTreeTest {

    @Test fun readsTheParentFromAStatLine() {
        val stat = "30085 (Runner.Listener) S 30081 27318 27318 0 -1 4194560 12345 0 0 0 42 7"
        assertEquals(30081, ProcessTree.ppidOf(stat))
    }

    @Test fun survivesAnExecutableNameContainingSpacesAndParentheses() {
        // /proc puts comm in parentheses without escaping anything inside it,
        // so splitting the whole line on spaces reads the wrong field.
        val stat = "27318 (lib proot (x).so) S 24113 27318 27318 0 -1 4194304 99 0 0 0 1 2"
        assertEquals(24113, ProcessTree.ppidOf(stat))
    }

    @Test fun refusesToGuessAtAnUnreadableStatLine() {
        assertNull(ProcessTree.ppidOf(""))
        assertNull(ProcessTree.ppidOf("not a stat line at all"))
    }

    @Test fun theTreeIsOrderedChildrenBeforeParents() {
        // app 100 -> proot 200 -> sh 300 -> Runner.Listener 400
        val parents = mapOf(200 to 100, 300 to 200, 400 to 300, 999 to 1)
        val tree = ProcessTree.descendantsOf(parents, root = 200)

        assertEquals(listOf(400, 300, 200), tree)
        // Signalling in this order means the runner is asked to leave before
        // proot disappears from under it.
        assertTrue(tree.indexOf(400) < tree.indexOf(200))
    }

    @Test fun unrelatedProcessesAreLeftAlone() {
        val parents = mapOf(200 to 100, 300 to 200, 500 to 1, 501 to 500)
        assertEquals(listOf(300, 200), ProcessTree.descendantsOf(parents, root = 200))
    }

    @Test fun aLeafIsItsOwnTree() {
        assertEquals(listOf(400), ProcessTree.descendantsOf(mapOf(400 to 300), root = 400))
    }

    @Test fun aSelfInconsistentSnapshotDoesNotLoopForever() {
        // /proc is read pid by pid, so a process that exits mid-scan can leave
        // a cycle behind. Terminating matters more than being right here.
        val parents = mapOf(200 to 300, 300 to 200)
        val tree = ProcessTree.descendantsOf(parents, root = 200)
        assertEquals(setOf(200, 300), tree.toSet())
    }

    // --- reading /proc -------------------------------------------------------
    //
    // The scan is the half that decides whether there is anything to stop at
    // all. When it matches nothing, haltListenerProcesses() sees an empty tree,
    // reports success, and the device announces it is holding jobs while the
    // listener is still connected to GitHub and can still be handed one — the
    // bug in issue #35, which took a device and fifteen minutes to notice.
    // These fixtures cost a millisecond.

    @get:Rule val proc = TemporaryFolder()

    /** A `/proc/<pid>` entry: a `stat` line naming [ppid], and a cmdline. */
    private fun process(pid: Int, ppid: Int, vararg argv: String) {
        val dir = File(proc.root, "$pid").apply { mkdirs() }
        File(dir, "stat").writeText("$pid (proot) S $ppid 1 1 0 -1 4194560 99 0 0 0 42 7\n")
        // The kernel separates and terminates the arguments with NUL, never
        // with spaces, so the fixture does too.
        File(dir, "cmdline").writeText(argv.joinToString("\u0000", postfix = "\u0000"))
    }

    private val libproot = "/data/app/io.github.m96chan.droidrunner/lib/arm64/libproot.so"

    @Test fun findsAProcessByASubstringOfItsCommandLine() {
        process(pid = 200, ppid = 100, libproot, "-r", "/data/rootfs", "bin/sh")
        process(pid = 700, ppid = 1, "/system/bin/logd")

        // The service searches for the proot path alone, never a whole line.
        assertEquals(listOf(200), ProcessTree.pidsMatching(libproot, proc.root))
    }

    @Test fun leavesEveryOtherProcessOnTheDeviceAlone() {
        process(pid = 700, ppid = 1, "/system/bin/logd")
        process(pid = 701, ppid = 1, "/system/bin/app_process", "com.example.other")

        // A marker that matched loosely would have the app signalling processes
        // it never started.
        assertEquals(emptyList<Int>(), ProcessTree.pidsMatching(libproot, proc.root))
    }

    @Test fun matchesAcrossTheNulSeparatorsProcWritesBetweenArguments() {
        process(pid = 200, ppid = 100, libproot, "-r", "/data/rootfs", "run.sh")

        val raw = File(proc.root, "200/cmdline").readText()
        assertTrue("the fixture must be NUL-separated, as /proc is", raw.contains('\u0000'))
        // Reading cmdline as text keeps the NULs in the string, so a marker has
        // to live inside one argument: the proot path does, a phrase spanning
        // two arguments never matches however the command line reads on screen.
        assertEquals(listOf(200), ProcessTree.pidsMatching(libproot, proc.root))
        assertEquals(emptyList<Int>(), ProcessTree.pidsMatching("$libproot -r", proc.root))
    }

    @Test fun ignoresTheEntriesInProcThatAreNotProcesses() {
        // Most of /proc is not a pid: self, net, meminfo, cpuinfo, ...
        File(proc.root, "self").mkdirs()
        File(proc.root, "net").mkdirs()
        File(proc.root, "meminfo").writeText("MemTotal: 1 kB\n")
        process(pid = 200, ppid = 100, libproot)

        assertEquals(listOf(200), ProcessTree.pidsMatching(libproot, proc.root))
        assertEquals(mapOf(200 to 100), ProcessTree.snapshotParents(proc.root))
    }

    @Test fun aProcessThatExitsMidScanDoesNotCostUsTheRestOfTheScan() {
        // /proc is listed first and read afterwards, so an entry can be gone by
        // the time we open it. A scan that threw here would abandon the tree
        // half-signalled, leaving a listener alive and holding its session.
        process(pid = 200, ppid = 100, libproot)
        File(proc.root, "300").mkdirs() // listed, then the process exited
        File(proc.root, "400/cmdline").mkdirs() // an entry that cannot be read
        File(proc.root, "400/stat").mkdirs()
        process(pid = 500, ppid = 100, libproot)

        assertEquals(listOf(200, 500), ProcessTree.pidsMatching(libproot, proc.root).sorted())
        assertEquals(mapOf(200 to 100, 500 to 100), ProcessTree.snapshotParents(proc.root))
    }

    @Test fun snapshotParentsReadsEveryProcessAndItsParent() {
        process(pid = 100, ppid = 1, "app_process", "io.github.m96chan.droidrunner")
        process(pid = 200, ppid = 100, libproot)
        process(pid = 300, ppid = 200, "/bin/sh", "run.sh")
        process(pid = 400, ppid = 300, "Runner.Listener", "run")

        assertEquals(
            mapOf(100 to 1, 200 to 100, 300 to 200, 400 to 300),
            ProcessTree.snapshotParents(proc.root),
        )
    }

    @Test fun theScanAndTheTreeComposeIntoTheProcessesTheServiceSignals() {
        // Exactly what RunnerService.listenerProcesses() does: find proot by its
        // path, then take everything under it.
        process(pid = 100, ppid = 1, "app_process", "io.github.m96chan.droidrunner")
        process(pid = 200, ppid = 100, libproot)
        process(pid = 300, ppid = 200, "/bin/sh", "run.sh")
        process(pid = 400, ppid = 300, "Runner.Listener", "run")
        process(pid = 700, ppid = 1, "/system/bin/logd")

        val tree = ProcessTree.pidsMatching(libproot, proc.root)
            .flatMap { ProcessTree.treeOf(it, proc.root) }
            .distinct()

        assertEquals(listOf(400, 300, 200), tree)
        // The listener is asked to leave before proot dies under it, and the app
        // itself must never appear in the list it is about to signal.
        assertTrue(tree.indexOf(400) < tree.indexOf(200))
        assertFalse(tree.contains(100))
    }

    @Test fun aliveReportsWhichPidsStillHaveAnEntry() {
        process(pid = 200, ppid = 100, libproot)

        assertTrue(ProcessTree.alive(200, proc.root))
        // awaitExit() polls this to decide whether SIGINT was enough; a pid
        // wrongly reported alive escalates to SIGKILL on a runner that already
        // left, and one wrongly reported gone ends the stop too early.
        assertFalse(ProcessTree.alive(300, proc.root))
    }
}
