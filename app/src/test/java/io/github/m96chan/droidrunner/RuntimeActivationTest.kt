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
        File(this, "rootfs/usr/bin").mkdirs()
        File(this, "rootfs/usr/bin/env").writeText("env")
        File(this, "home/runner").mkdirs()
        File(this, "home/runner/run.sh").writeText("runner")
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
        // A completed swap can leave its backup behind before cleanup.
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

    @Test fun restartAfterSettingAsideTheOldRuntimeRestoresIdentityAndIsIdempotent() {
        val target = runtimeAt(File(temp.root, "runtime"), "old")
        File(target, "runner-config.json").writeText("registration")
        File(target, "home/runner/.runner").writeText("identity")
        val previous = File(temp.root, "runtime.old")
        val staging = runtimeAt(File(temp.root, "runtime.new"), "new")
        val failure = runCatching {
            RuntimeActivation.activate(staging, target, previous, rename = { from, to ->
                if (from == staging) error("simulated process death")
                from.renameTo(to)
            }, valid = RuntimeActivation::isRuntime)
        }
        assertTrue(failure.isFailure)
        assertFalse(target.exists())
        repeat(2) { RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime) }
        assertEquals("old", File(target, ".installed").readText())
        assertEquals("registration", File(target, "runner-config.json").readText())
        assertEquals("identity", File(target, "home/runner/.runner").readText())
    }

    @Test fun restartAfterPublishingTheNewRuntimeDefersBackupCleanupUntilInstallation() {
        val target = runtimeAt(File(temp.root, "runtime"), "new")
        val previous = runtimeAt(File(temp.root, "runtime.old"), "old")
        File(previous, "workspace").mkdirs()
        val oldArtifact = File(previous, "workspace/artifact").apply { writeText("build output") }
        repeat(2) { RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime) }
        assertEquals("new", File(target, ".installed").readText())
        assertTrue("startup does not recursively delete the old tree", previous.isDirectory)
        assertEquals("build output", oldArtifact.readText())
        RuntimeActivation.recover(
            target, previous, RuntimeActivation::isRuntime, cleanupPrevious = true,
        )
        assertFalse(previous.exists())
    }

    @Test fun nextInstallRecoversRegistrationBeforePreparingTheNewTree() {
        val target = File(temp.root, "runtime")
        val previous = runtimeAt(File(temp.root, "runtime.old"), "old")
        File(previous, "runner-config.json").writeText("registration")
        val staging = runtimeAt(File(temp.root, "runtime.new"), "new")
        RuntimeActivation.activate(staging, target, previous,
            valid = RuntimeActivation::isRuntime,
            prepare = { File(target, "runner-config.json").copyTo(File(staging, "runner-config.json")) },
        )
        assertEquals("new", File(target, ".installed").readText())
        assertEquals("registration", File(target, "runner-config.json").readText())
    }

    @Test fun installationCollectsInterruptedCleanupWithoutTouchingOtherTrees() {
        val target = runtimeAt(File(temp.root, "runtime"), "new")
        val previous = File(temp.root, "runtime.old")
        val abandoned = runtimeAt(File(temp.root, "runtime.old.cleanup-abandoned"), "old")
        val unrelated = runtimeAt(File(temp.root, "qnn.old.cleanup-abandoned"), "qnn")
        RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime)
        assertTrue("startup leaves deletion to installation", abandoned.isDirectory)
        RuntimeActivation.recover(
            target, previous, RuntimeActivation::isRuntime, cleanupPrevious = true,
        )
        assertFalse(abandoned.exists())
        assertTrue(unrelated.isDirectory)
        assertEquals("new", File(target, ".installed").readText())
    }

    @Test fun failedRollbackRetainsBackupForNextStartup() {
        val target = runtimeAt(File(temp.root, "runtime"), "old")
        val previous = File(temp.root, "runtime.old")
        val staging = runtimeAt(File(temp.root, "runtime.new"), "new")
        val failure = runCatching {
            RuntimeActivation.activate(staging, target, previous, rename = { from, to ->
                if (from == target) from.renameTo(to) else false
            }, valid = RuntimeActivation::isRuntime)
        }
        assertTrue(failure.isFailure)
        assertTrue(previous.isDirectory)
        RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime)
        assertEquals("old", File(target, ".installed").readText())
    }

    @Test fun incompleteTargetIsPreservedWhileAValidatedBackupIsRestored() {
        val target = File(temp.root, "runtime").apply { mkdirs() }
        File(target, "runner-config.json").writeText("partial target registration")
        val previous = runtimeAt(File(temp.root, "runtime.old"), "old")
        RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime)
        assertEquals("old", File(target, ".installed").readText())
        assertEquals("partial target registration", File(temp.root, "runtime.failed/runner-config.json").readText())
    }

    @Test fun incompleteBackupIsNeverDeletedOrPromoted() {
        val previous = File(temp.root, "runtime.old").apply { mkdirs() }
        File(previous, "runner-config.json").writeText("registration")
        val target = File(temp.root, "runtime")
        val failure = runCatching {
            RuntimeActivation.recover(target, previous, RuntimeActivation::isRuntime)
        }
        assertTrue(failure.isFailure)
        assertFalse(target.exists())
        assertEquals("registration", File(previous, "runner-config.json").readText())
    }

    @Test fun failedRecoveryPreventsAnInstallFromDeletingTheOnlyGoodBackup() {
        val previous = runtimeAt(File(temp.root, "runtime.old"), "old")
        File(previous, "runner-config.json").writeText("registration")
        val target = File(temp.root, "runtime")
        val staging = runtimeAt(File(temp.root, "runtime.new"), "new")
        val failure = runCatching {
            RuntimeActivation.activate(staging, target, previous,
                rename = { _, _ -> false }, valid = RuntimeActivation::isRuntime,
            )
        }
        assertTrue(failure.isFailure)
        assertFalse(target.exists())
        assertEquals("registration", File(previous, "runner-config.json").readText())
        assertEquals("new", File(staging, ".installed").readText())
    }
}
