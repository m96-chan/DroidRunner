package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
