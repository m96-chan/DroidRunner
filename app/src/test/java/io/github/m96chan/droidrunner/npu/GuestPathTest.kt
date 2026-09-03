package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Job code chooses this path, so the mapping is a trust boundary: it must not
 * become a way to read arbitrary files off the device.
 */
class GuestPathTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var home: File

    private fun setUp(): File {
        runtimeDir = temp.newFolder("runtime")
        home = File(runtimeDir, "home/runner").apply { mkdirs() }
        return home
    }

    @Test fun resolvesAFileInsideTheRunnerHome() {
        val home = setUp()
        val model = File(home, "_work/repo/model.tflite").apply {
            parentFile?.mkdirs()
            writeText("model")
        }
        assertEquals(model.canonicalFile, GuestPath.resolve(runtimeDir, "/home/runner/_work/repo/model.tflite"))
    }

    @Test fun rejectsTraversalOutOfTheHome() {
        setUp()
        val secret = File(runtimeDir, "secret.txt").apply { writeText("nope") }
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner/../secret.txt"))
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner/_work/../../secret.txt"))
        assertEquals("nope", secret.readText())
    }

    @Test fun rejectsPathsOutsideTheGuestHome() {
        setUp()
        assertNull(GuestPath.resolve(runtimeDir, "/etc/passwd"))
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner"))
        assertNull(GuestPath.resolve(runtimeDir, "relative/model.tflite"))
        assertNull(GuestPath.resolve(runtimeDir, "/home/runnerother/model.tflite"))
    }

    @Test fun rejectsASymlinkPointingOutside() {
        val home = setUp()
        val outside = temp.newFile("outside.tflite").apply { writeText("secret") }
        Files.createSymbolicLink(File(home, "link.tflite").toPath(), outside.toPath())
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner/link.tflite"))
    }

    @Test fun rejectsDirectoriesAndMissingFiles() {
        val home = setUp()
        File(home, "_work").mkdirs()
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner/_work"))
        assertNull(GuestPath.resolve(runtimeDir, "/home/runner/absent.tflite"))
    }
}
