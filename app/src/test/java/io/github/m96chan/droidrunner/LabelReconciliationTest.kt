package io.github.m96chan.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelReconciliationTest {

    @Test fun aDeviceThatStillDescribesItselfCorrectlyIsLeftAlone() {
        val labels = setOf("android", "arm64", "android-api-36", "npu-neuron")
        assertFalse(LabelReconciliation.needsUpdate(labels, labels))
    }

    @Test fun orderIsNotAChange() {
        // GitHub returns labels in its own order; a set comparison keeps that
        // from looking like drift and rewriting the labels on every start.
        assertFalse(
            LabelReconciliation.needsUpdate(
                setOf("android", "npu-neuron", "arm64"),
                setOf("arm64", "android", "npu-neuron"),
            ),
        )
    }

    @Test fun aLabelTheDeviceNoLongerBelievesCountsAsDrift() {
        // The case this exists for: a Snapdragon 8 Gen 3 still carrying
        // android-no-npu, written by a build from before #23 fixed the SoC
        // matching. Nothing recomputed it, so it was skipped by every workflow
        // selecting android-npu.
        assertTrue(
            LabelReconciliation.needsUpdate(
                current = setOf("android", "arm64", "android-npu", "npu-qnn"),
                registered = setOf("android", "arm64", "android-no-npu"),
            ),
        )
    }

    @Test fun githubsOwnLabelsAreNeitherComparedNorSentBack() {
        // self-hosted and the platform labels are assigned by GitHub. Sending
        // them back as custom labels duplicates them; comparing them makes
        // every device look like it has drifted.
        assertFalse(
            LabelReconciliation.needsUpdate(
                current = setOf("android", "arm64"),
                registered = setOf("self-hosted", "Linux", "ARM64", "android", "arm64"),
            ),
        )
        assertEquals(listOf("android"), LabelReconciliation.payload(setOf("self-hosted", "arm64", "android")))
    }

    @Test fun thePayloadIsSortedSoTwoRunsLookTheSame() {
        assertEquals(
            listOf("android", "android-api-36", "npu-neuron"),
            LabelReconciliation.payload(setOf("npu-neuron", "android", "android-api-36")),
        )
    }
}
