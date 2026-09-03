package io.github.m96chan.droidrunner.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeActivationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun runtimeAt(dir: File, version: String) = dir.apply {
        mkdirs()
        File(this, ".installed").writeText(version)
    }

    @Test fun theNewRuntimeReplacesTheOldOne() {
        val staging = runtimeAt(temp.newFolder("staging"), "new")
        val target = runtimeAt(temp.newFolder("runtime"), "old")
        val previous = File(temp.root, "runtime.old")

        RuntimeActivation.activate(staging, target, previous)

        assertEquals("new", File(target, ".installed").readText())
        assertFalse("the set-aside copy is cleaned up", previous.exists())
        assertFalse("staging is consumed", staging.exists())
    }

    @Test fun aFailedActivationLeavesTheWorkingRuntimeInPlace() {
        // The failure this guards against: the device is left with no runtime
        // at all, and the only way back is another ~200MB download.
        val staging = runtimeAt(temp.newFolder("staging"), "new")
        val target = runtimeAt(temp.newFolder("runtime"), "old")
        val previous = File(temp.root, "runtime.old")

        var calls = 0
        val renameThatFailsOnTheSecondMove = { from: File, to: File ->
            calls++
            if (calls == 2) false else from.renameTo(to)
        }

        val failure = runCatching {
            RuntimeActivation.activate(staging, target, previous, renameThatFailsOnTheSecondMove)
        }

        assertTrue(failure.isFailure)
        assertTrue("the old runtime is back", target.isDirectory)
        assertEquals("old", File(target, ".installed").readText())
    }

    @Test fun installingWithNothingInPlaceYetWorks() {
        val staging = runtimeAt(temp.newFolder("staging"), "first")
        val target = File(temp.root, "runtime")
        val previous = File(temp.root, "runtime.old")

        RuntimeActivation.activate(staging, target, previous)

        assertEquals("first", File(target, ".installed").readText())
    }

    @Test fun refusesToActivateSomethingThatIsNotThere() {
        val target = runtimeAt(temp.newFolder("runtime"), "old")

        val failure = runCatching {
            RuntimeActivation.activate(File(temp.root, "missing"), target, File(temp.root, "old"))
        }

        assertTrue(failure.isFailure)
        assertEquals("old", File(target, ".installed").readText())
    }

    @Test fun aLeftoverSetAsideCopyDoesNotBlockTheNextInstall() {
        // An install killed part-way can leave one behind; it is stale by
        // definition and must not be mistaken for a backup worth keeping.
        val staging = runtimeAt(temp.newFolder("staging"), "new")
        val target = runtimeAt(temp.newFolder("runtime"), "old")
        val previous = runtimeAt(File(temp.root, "runtime.old"), "ancient")

        RuntimeActivation.activate(staging, target, previous)

        assertEquals("new", File(target, ".installed").readText())
        assertFalse(previous.exists())
    }

    @Test fun spaceIsDemandedForTheArchiveAndWhatItExpandsInto() {
        // A rootfs expands to roughly three times its compressed size, and the
        // archive is still on disk while it does.
        assertTrue(RuntimeActivation.requiredBytes(200_000_000) >= 800_000_000)
    }
}
