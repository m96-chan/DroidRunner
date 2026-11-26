package io.github.m96chan.droidrunner.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Job code owns everything under the guest, so each of these plants the link
 * or swap a job could, and checks that nothing outside the guest changed.
 * They run the real native code, built for the host.
 */
class GuestFsTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var home: File

    private fun setUp() {
        runtimeDir = temp.newFolder("runtime")
        home = File(runtimeDir, "home/runner").apply { mkdirs() }
    }

    private fun link(at: File, target: File) {
        at.parentFile?.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    private fun stage() = temp.newFolder()

    @Test fun copiesAJobFileUnderItsOwnName() {
        setUp()
        File(home, "_work/repo/model.tflite").apply { parentFile?.mkdirs(); writeText("model") }
        val copy = GuestFs.stageJobFile(runtimeDir, "/home/runner/_work/repo/model.tflite", stage())
        assertEquals("model.tflite", copy.name)
        assertEquals("model", copy.readText())
    }

    @Test fun aNonAsciiNameSurvivesTheTripThroughJni() {
        setUp()
        File(home, "モデル/重み.tflite").apply { parentFile?.mkdirs(); writeText("model") }
        val copy = GuestFs.stageJobFile(runtimeDir, "/home/runner/モデル/重み.tflite", stage())
        assertEquals("重み.tflite", copy.name)
        assertEquals("model", copy.readText())
    }

    @Test fun aSparseFileLargerThanTheFreeSpaceIsNotCopied() {
        setUp()
        val stage = stage()
        java.io.RandomAccessFile(File(home, "sparse.bin"), "rw").use { it.setLength(stage.usableSpace + (1L shl 30)) }
        val refused = assertThrows(java.io.IOException::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/sparse.bin", stage)
        }
        assertTrue(refused.message!!, refused.message!!.contains("can be staged"))
        assertFalse(File(stage, "sparse.bin").exists())
    }

    @Test fun refusesASymlinkAsTheFileAndNamesIt() {
        setUp()
        val secret = temp.newFile("secret").apply { writeText("secret") }
        link(File(home, "model.tflite"), secret)
        val refused = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/model.tflite", stage())
        }
        assertTrue(refused.message!!, refused.message!!.startsWith("/home/runner/model.tflite is a symlink"))
    }

    @Test fun refusesASymlinkEvenWhenItPointsInsideTheHome() {
        setUp()
        File(home, "real.tflite").writeText("model")
        link(File(home, "model.tflite"), File("real.tflite"))
        assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/model.tflite", stage())
        }
    }

    @Test fun refusesASymlinkedDirectoryOnTheWay() {
        setUp()
        val outside = temp.newFolder("outside").apply { File(this, "model.tflite").writeText("secret") }
        link(File(home, "_work"), outside)
        val refused = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/_work/model.tflite", stage())
        }
        assertTrue(refused.message!!, refused.message!!.startsWith("/home/runner/_work is a symlink"))
    }

    @Test fun refusesARunnerHomeReplacedByASymlink() {
        // PRoot binds the whole of /home into the guest, so a job can replace
        // /home/runner itself.
        runtimeDir = temp.newFolder("runtime")
        val outside = temp.newFolder("outside").apply { File(this, "model.tflite").writeText("secret") }
        link(File(runtimeDir, "home/runner"), outside)
        val refused = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/model.tflite", stage())
        }
        assertTrue(refused.message!!, refused.message!!.startsWith("/home/runner is a symlink"))
    }

    @Test fun refusesPathsOutsideTheHomeAndTraversal() {
        setUp()
        listOf(
            "/etc/passwd", "/home/runner", "/home/runner/", "relative/model.tflite",
            "/home/runnerother/model.tflite", "/home/runner/../secret", "/home/runner/a/../../secret",
        ).forEach { path ->
            assertThrows(path, GuestFs.Refused::class.java) {
                GuestFs.stageJobFile(runtimeDir, path, stage())
            }
        }
    }

    @Test fun refusesDirectoriesAndMissingFiles() {
        setUp()
        File(home, "_work").mkdirs()
        val directory = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/_work", stage())
        }
        assertEquals("/home/runner/_work is not a regular file", directory.message)
        val missing = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/absent.tflite", stage())
        }
        assertEquals("/home/runner/absent.tflite does not exist", missing.message)
    }

    @Test(timeout = 10_000)
    fun refusesAFifoWithoutHanging() {
        setUp()
        val fifo = File(home, "model.tflite")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        assertThrows(GuestFs.Refused::class.java) {
            GuestFs.stageJobFile(runtimeDir, "/home/runner/model.tflite", stage())
        }
    }

    @Test fun createsAMissingOutputDirectory() {
        setUp()
        GuestFs.openJobDirectory(runtimeDir, "/home/runner/_work/out/a").use { }
        assertTrue(File(home, "_work/out/a").isDirectory)
    }

    @Test fun anExistingFileIsNotAnOutputDirectory() {
        setUp()
        File(home, "taken").writeText("x")
        val refused = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.openJobDirectory(runtimeDir, "/home/runner/taken")
        }
        assertEquals("/home/runner/taken is not a directory", refused.message)
    }

    @Test fun refusesAnOutputDirectoryOutsideTheHome() {
        setUp()
        listOf("/home/runner/../../escape", "/etc", "relative/path").forEach { path ->
            assertThrows(path, GuestFs.Refused::class.java) { GuestFs.openJobDirectory(runtimeDir, path) }
        }
        assertFalse(File(runtimeDir, "escape").exists())
    }

    @Test fun refusesASymlinkedOutputDirectory() {
        setUp()
        val outside = temp.newFolder("outside")
        link(File(home, "out"), outside)
        val refused = assertThrows(GuestFs.Refused::class.java) {
            GuestFs.openJobDirectory(runtimeDir, "/home/runner/out/a")
        }
        assertTrue(refused.message!!, refused.message!!.startsWith("/home/runner/out is a symlink"))
        assertFalse(File(outside, "a").exists())
    }

    @Test fun publishingReplacesAPlantedSymlinkAndLeavesItsTargetAlone() {
        setUp()
        val victim = temp.newFile("victim").apply { writeText("app data") }
        link(File(home, "out/output-0.bin"), victim)
        val staged = File(GuestFs.newStage(runtimeDir).directory, "output-0.bin").apply { writeText("tensor") }
        GuestFs.openJobDirectory(runtimeDir, "/home/runner/out").use { out ->
            GuestFs.publish(staged, out, "output-0.bin")
        }
        assertEquals("app data", victim.readText())
        val published = File(home, "out/output-0.bin")
        assertFalse(Files.isSymbolicLink(published.toPath()))
        assertEquals("tensor", published.readText())
    }

    @Test fun aHeldDirectoryIsNotRedirectedByASwap() {
        setUp()
        val outside = temp.newFolder("outside")
        val staged = File(GuestFs.newStage(runtimeDir).directory, "output-0.bin").apply { writeText("tensor") }
        GuestFs.openJobDirectory(runtimeDir, "/home/runner/out").use { out ->
            // The job moves the directory away and plants a link in its place.
            assertTrue(File(home, "out").renameTo(File(home, "moved")))
            link(File(home, "out"), outside)
            GuestFs.publish(staged, out, "output-0.bin")
        }
        assertEquals(0, outside.list()!!.size)
        assertEquals("tensor", File(home, "moved/output-0.bin").readText())
    }

    @Test fun removingATreeDeletesLinksWithoutEnteringThem() {
        setUp()
        val outside = temp.newFolder("outside")
        val kept = File(outside, "keep").apply { writeText("app data") }
        val keptFile = temp.newFile("keptFile").apply { writeText("app data") }
        File(home, "_work/repo/deep/er").mkdirs()
        File(home, "_work/repo/deep/er/file").writeText("job")
        link(File(home, "_work/repo/dirLink"), outside)
        link(File(home, "_work/fileLink"), keptFile)
        GuestFs.removeTree(runtimeDir, GuestFs.RUNNER_HOME + "_work")
        assertFalse(File(home, "_work").exists())
        assertEquals("app data", kept.readText())
        assertEquals("app data", keptFile.readText())
    }

    @Test fun removingATreeWithReadOnlyAndLockedDirectories() {
        // An ordinary job leaves these (a Go module cache is read-only), and a
        // cleanup that fails stops the next registration.
        setUp()
        val readOnly = File(home, "_work/cache/mod").apply { mkdirs() }
        File(readOnly, "file").writeText("job")
        val locked = File(home, "_work/locked").apply { mkdirs() }
        File(locked, "file").writeText("job")
        Files.setPosixFilePermissions(readOnly.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
        Files.setPosixFilePermissions(locked.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("---------"))
        GuestFs.removeTree(runtimeDir, GuestFs.RUNNER_HOME + "_work")
        assertFalse(File(home, "_work").exists())
    }

    @Test fun removingAMissingTreeIsNotAnError() {
        setUp()
        GuestFs.removeTree(runtimeDir, GuestFs.RUNNER_HOME + "_work")
    }

    @Test fun removingThroughASymlinkedParentIsRefused() {
        runtimeDir = temp.newFolder("runtime")
        val outside = temp.newFolder("outside").apply { File(this, "_work").mkdirs() }
        link(File(runtimeDir, "home/runner"), outside)
        assertThrows(GuestFs.Refused::class.java) {
            GuestFs.removeTree(runtimeDir, GuestFs.RUNNER_HOME + "_work")
        }
        assertTrue(File(outside, "_work").isDirectory)
    }
}
