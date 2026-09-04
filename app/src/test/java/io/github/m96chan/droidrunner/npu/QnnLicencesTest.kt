package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnLicencesTest {

    @Test fun bothPackagesTermsHaveToBeAccepted() {
        // The two artifacts are covered by different licences from different
        // Qualcomm entities. Showing one and installing both would be asking
        // about the wrong document.
        assertEquals(
            listOf(QnnArtifacts.Module.RUNTIME, QnnArtifacts.Module.DELEGATE),
            QnnLicences.required.map { it.module },
        )
        assertEquals(2, QnnLicences.required.map { it.licensor }.distinct().size)
        assertEquals(2, QnnLicences.required.map { it.sha256 }.distinct().size)
    }

    @Test fun eachDocumentIsKeptUnderItsOwnName() {
        // Both live at LICENSE.pdf inside their package, so writing them to
        // one filename would leave whichever landed second standing in for
        // both.
        assertEquals(listOf("LICENSE.pdf", "LICENSE.pdf"), QnnLicences.required.map { it.zipEntry })
        assertEquals(2, QnnLicences.required.map { it.fileName }.distinct().size)
    }

    @Test fun nothingCountsAsAcceptanceUntilSomeoneAccepts() {
        assertFalse(QnnLicences.accepted(null))
        assertFalse(QnnLicences.accepted(""))
        // A device that stored a bare "true" under some older scheme has not
        // agreed to these documents, and must be asked.
        assertFalse(QnnLicences.accepted("true"))
    }

    @Test fun whatWasAcceptedIsWhatIsRequiredNow() {
        assertTrue(QnnLicences.accepted(QnnLicences.fingerprint()))
    }

    @Test fun anAcceptanceOfDifferentTermsDoesNotCarryOver() {
        // The fingerprint is the documents' own digests, so terms that changed
        // under a user invalidate their agreement rather than inheriting it.
        val stale = QnnLicences.fingerprint().replace('a', 'b')

        assertFalse(QnnLicences.accepted(stale))
    }

    @Test fun acceptanceIsNotTiedToTheQnnReleaseNumber() {
        // Bumping 2.49.0 to 2.50.0 with the same terms must not make everyone
        // agree again: what was accepted is the text, not the version.
        assertFalse(QnnLicences.fingerprint().contains(QnnArtifacts.VERSION))
    }

    @Test fun theScreenPointsAtTheDocumentRatherThanParaphrasingIt() {
        // Someone acting on our summary would be acting on the wrong text, so
        // the obligations name the areas and the PDF carries the wording.
        assertTrue(QnnLicences.obligations.size >= 3)
        assertTrue(QnnLicences.obligations.any { it.contains("Export") })
    }
}
