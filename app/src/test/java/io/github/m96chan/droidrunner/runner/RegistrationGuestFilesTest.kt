package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The registration code reads and deletes files a job can replace. Each case
 * plants the link a job could, and checks that nothing outside the guest is
 * read or deleted.
 */
class RegistrationGuestFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val runtimeDir by lazy { temp.newFolder("runtime") }
    private val home by lazy { File(runtimeDir, "home/runner").apply { mkdirs() } }

    private fun link(at: File, target: File) {
        at.parentFile?.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    @Test fun theEphemeralMarkerIsReadFromTheRealFile() {
        // The .NET runner writes the file with a byte order mark.
        File(home, ".runner").writeText(Char(0xFEFF) + "{\"isEphemeral\":true}")
        assertTrue(RunnerRegistration.registeredAsEphemeral(runtimeDir))
    }

    @Test fun aSymlinkedMarkerIsNotRead() {
        val elsewhere = temp.newFile("elsewhere").apply { writeText("{\"ephemeral\":true}") }
        link(File(home, ".runner"), elsewhere)
        assertFalse(RunnerRegistration.registeredAsEphemeral(runtimeDir))
    }

    @Test fun clearingTheIdentityRemovesItsFilesAndLeavesTheRest() {
        File(home, ".runner").writeText("{}")
        File(home, ".credentials_rsaparams").writeText("{}")
        File(home, "run.sh").writeText("#!/bin/sh")
        RunnerRegistration.clearLocalRegistration(runtimeDir)
        assertEquals(listOf("run.sh"), home.list()!!.toList())
    }

    @Test fun clearingTheIdentityThroughASymlinkedHomeDeletesNothingOutside() {
        val outside = temp.newFolder("outside").apply { File(this, ".credentials").writeText("app data") }
        File(runtimeDir, "home").mkdirs()
        link(File(runtimeDir, "home/runner"), outside)
        RunnerRegistration.clearLocalRegistration(runtimeDir)
        assertEquals("app data", File(outside, ".credentials").readText())
    }

    @Test fun aWorkDirectoryThatCannotBeCleanedStopsTheNextRegistration() {
        val outside = temp.newFolder("outside").apply { File(this, "_work").mkdirs() }
        File(runtimeDir, "home").mkdirs()
        link(File(runtimeDir, "home/runner"), outside)
        assertThrows(IOException::class.java) { RunnerRegistration.cleanWorkDirectory(runtimeDir) }
        assertTrue(File(outside, "_work").isDirectory)
    }
}
