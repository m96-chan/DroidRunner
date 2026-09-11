package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Attribution across several delegates (issue #170).
 *
 * The first two cases here are real, from an MT6899 running the #159
 * measurement, and both came back `cpu-fallback` while nothing touched the CPU.
 *
 * The XNNPACK cases are the answer going wrong in the other direction (#189).
 * TFLite attaches its own CPU delegate after the caller's, and announces what it
 * took in the same words the parser matches, so its claim arrives in the same
 * list as the accelerators'. Counting it made a run the CPU finished an
 * accelerator run — which is the claim the whole file exists to refuse, so it is
 * pinned from both sides: the CPU's share must not be counted, and the
 * accelerators' share must still be.
 */
class MultiDelegateAttributionTest {

    private fun claim(delegate: String, delegated: Int, total: Int) =
        Delegation(delegated = delegated, total = total, partitions = 1, delegate = delegate)

    @Test fun oneDelegateDecliningDoesNotMakeTheOtherACpuFallback() {
        // conv2d_3x3: NNAPI took nothing and printed nothing, the GPU ran the
        // whole graph, and the single-delegate rule called that a fallback
        // because the delegate was not the one a pinned NNAPI device implies.
        val (executed, by) = executedForAll(listOf(claim("TfLiteGpuDelegateV2", 1, 1)))

        assertEquals("accelerator", executed)
        assertEquals("TfLiteGpuDelegateV2", by)
    }

    @Test fun twoDelegatesTakingHalfEachIsNotAFallbackEither() {
        // conv2d_5x5: PAD + CONV_2D, one operator each, nothing left over.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 1, 2), claim("TfLiteGpuDelegateV2", 1, 2)),
        )

        assertEquals("accelerator", executed)
        assertEquals("TfLiteNnapiDelegate+TfLiteGpuDelegateV2", by)
    }

    @Test fun theUnionIsCountedAgainstTheGraphAsItArrived() {
        // A delegate node left by an earlier pass is not claimable by a later
        // one, so the counts are disjoint and the first entry's total is the
        // original node count. Summing against the *last* total would call this
        // over-delegated.
        val (executed, _) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 2, 3), claim("TfLiteGpuDelegateV2", 1, 2)),
        )

        assertEquals("accelerator", executed)
    }

    @Test fun somethingLeftBehindIsStillPartial() {
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 1, 4), claim("TfLiteGpuDelegateV2", 1, 4)),
        )

        assertEquals("partial", executed)
        assertEquals("TfLiteNnapiDelegate+TfLiteGpuDelegateV2", by)
    }

    @Test fun neitherTakingAnythingIsTheOnlyRealFallback() {
        assertEquals("cpu-fallback" to "cpu", executedForAll(emptyList()))
        assertEquals(
            "cpu-fallback" to "cpu",
            executedForAll(listOf(claim("TfLiteNnapiDelegate", 0, 2))),
        )
    }

    @Test fun aRunXnnpackFinishedIsNotAnAcceleratorRun() {
        // The #189 reproduction: `mtk-mdla_shim+gpu` on a float model both
        // named accelerators declined, TFLite applied XNNPACK last by default,
        // and its line was the only one in the log. Summing every claim made
        // 64 of 64 and reported a 100% CPU run as `accelerator`, attributed to
        // `TfLiteXNNPackDelegate`.
        val (executed, by) = executedForAll(listOf(claim("TfLiteXNNPackDelegate", 64, 64)))

        assertEquals("cpu-fallback", executed)
        assertEquals("cpu", by)
    }

    @Test fun anAcceleratorThatPrintedAnEmptyClaimIsStillACpuFallbackBesideXnnpack() {
        // The same run where the NNAPI delegate does say so rather than staying
        // silent. Nothing changes: a delegate that took nothing does not name
        // the run, and the delegate that took everything is the CPU.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 0, 64), claim("TfLiteXNNPackDelegate", 64, 64)),
        )

        assertEquals("cpu-fallback", executed)
        assertEquals("cpu", by)
    }

    @Test fun whatXnnpackPickedUpIsWhatWasLeftBehind() {
        // NNAPI takes 30 of 64 and the GPU delegate declines, so XNNPACK
        // finishes the other 34. That is exactly `partial` — half the timing is
        // a CPU number — and the accelerator that did the work names it alone.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 30, 64), claim("TfLiteXNNPackDelegate", 34, 64)),
        )

        assertEquals("partial", executed)
        assertEquals("TfLiteNnapiDelegate", by)
    }

    @Test fun twoAcceleratorsCoveringTheGraphAreStillAnAcceleratorRun() {
        // The guard has to bite on XNNPACK and nothing else. With no CPU line
        // in the log there is nothing to drop, and a partitioned run that left
        // the CPU nothing to do is the case #170 was filed about.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 30, 64), claim("TfLiteGpuDelegateV2", 34, 64)),
        )

        assertEquals("accelerator", executed)
        assertEquals("TfLiteNnapiDelegate+TfLiteGpuDelegateV2", by)
    }

    @Test fun aDelegateThatClaimedNothingIsNotNamedAsHavingRunIt() {
        // The other half of #170: `executedBy` named the delegate that went
        // first, whether or not it took anything.
        val (_, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 0, 1), claim("TfLiteGpuDelegateV2", 1, 1)),
        )

        assertEquals("TfLiteGpuDelegateV2", by)
    }

    @Test fun theNnapiReferenceDriverIsNotAnAcceleratorInTheUnionEither() {
        // `executedFor` has refused this driver since #93. The union rule could
        // not, because the log names the delegate and not the driver behind it,
        // so `nnapi-reference+gpu` reported the CPU as an accelerator. The
        // requested devices are what separate them: only one NNAPI delegate is
        // attached here and the caller named the driver it was given.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 64, 64)),
            listOf("nnapi-reference", "gpu"),
        )

        assertEquals("cpu-fallback", executed)
        assertEquals("cpu", by)
    }

    @Test fun theGpuStillCountsWhenTheOtherHalfOfThePairIsTheCpuReference() {
        // The guard must drop the reference driver and nothing else: the GPU
        // finishing what the CPU reference was offered first is a real
        // accelerator run, and calling it a fallback would be #140 again.
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 30, 64), claim("TfLiteGpuDelegateV2", 34, 64)),
            listOf("nnapi-reference", "gpu"),
        )

        assertEquals("partial", executed)
        assertEquals("TfLiteGpuDelegateV2", by)
    }

    @Test fun aRealNnapiDriverIsUnaffectedByTheGuard() {
        val (executed, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 64, 64)),
            listOf("qti-dsp", "gpu"),
        )

        assertEquals("accelerator", executed)
        assertEquals("TfLiteNnapiDelegate", by)
    }

    @Test fun twoNnapiDriversOneOfThemTheCpuCannotBeToldApart() {
        // Both print `TfLiteNnapiDelegate`, so nothing in the log says which
        // entry was the DSP. `unknown` is what the contract already has for a
        // delegate that did not say what it took, and consumers are told to
        // treat it as not-accelerated — the safe direction to be wrong in.
        val (executed, _) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 64, 64)),
            listOf("nnapi-reference", "qti-dsp"),
        )

        assertEquals("unknown", executed)
    }
}
