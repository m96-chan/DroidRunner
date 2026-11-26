package io.github.m96chan.droidrunner.qnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QnnIntegrityTest {
    @get:Rule val temp = TemporaryFolder()

    // SHA-256 of "library".
    private val pin = "b718f1354f7247312eca086d9a024afe5fa717ddea5adeddd6f12bcf945b2e8c"
    private val pins = { _: String -> pin }

    @Test fun aReplacedLibraryIsRefusedAndNamed() {
        val directory = temp.newFolder().apply { File(this, "libQnnHtp.so").writeText("planted") }
        assertEquals(
            "libQnnHtp.so does not match its pinned SHA-256; reinstall the QNN runtime",
            QnnIntegrity.problem(directory, listOf("libQnnHtp.so")),
        )
    }

    @Test fun matchingLibrariesPass() {
        val directory = temp.newFolder().apply { File(this, "libQnnHtp.so").writeText("library") }
        assertNull(QnnIntegrity.problem(directory, listOf("libQnnHtp.so"), pins))
    }

    @Test fun aLibraryWithNoPinIsRefused() {
        val directory = temp.newFolder().apply { File(this, "libOther.so").writeText("library") }
        assertEquals(
            "libOther.so has no pinned SHA-256; reinstall the QNN runtime",
            QnnIntegrity.problem(directory, listOf("libOther.so")),
        )
    }

    @Test fun anUnreadableLibraryIsAnAnswerNotACrash() {
        // The probe runs on the :qnn main looper, where a throw kills the process.
        val directory = temp.newFolder()
        val problem = QnnIntegrity.problem(directory, listOf("libQnnHtp.so"), pins)
        assertTrue(problem!!, problem.startsWith("cannot read libQnnHtp.so"))
    }

    @Test fun theSkelTheDspWillReadIsCheckedWithItsStub() {
        val directory = temp.newFolder().apply {
            File(this, "libQnnHtpV75Stub.so").writeText("library")
            File(this, "libQnnHtpV75Skel.so").writeText("planted")
        }
        assertEquals(
            "libQnnHtpV75Skel.so does not match its pinned SHA-256; reinstall the QNN runtime",
            QnnIntegrity.problem(directory, listOf("libQnnHtpV75Stub.so"), pins),
        )
    }

    @Test fun onlyALoadedLibraryIsTrustedLater() {
        val directory = temp.newFolder()
        val loadedOne = File(directory, "libQnnHtp.so").apply { writeText("library") }
        val notLoaded = File(directory, "libQnnGpu.so").apply { writeText("library") }
        assertNull(QnnIntegrity.problem(directory, listOf("libQnnHtp.so", "libQnnGpu.so"), pins))
        QnnIntegrity.loaded(directory, listOf("libQnnHtp.so"))

        // dlopen keeps what it loaded, so a later change cannot reach it.
        loadedOne.writeText("changed after it was loaded")
        assertNull(QnnIntegrity.problem(directory, listOf("libQnnHtp.so"), pins))
        // A library checked but never loaded is checked again.
        notLoaded.writeText("planted")
        assertEquals(
            "libQnnGpu.so does not match its pinned SHA-256; reinstall the QNN runtime",
            QnnIntegrity.problem(directory, listOf("libQnnGpu.so"), pins),
        )
    }
}
