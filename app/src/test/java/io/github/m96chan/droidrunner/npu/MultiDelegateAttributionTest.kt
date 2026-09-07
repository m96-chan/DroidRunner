package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Attribution across several delegates (issue #170).
 *
 * Both cases here are real, from an MT6899 running the #159 measurement, and
 * both came back `cpu-fallback` while nothing touched the CPU.
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

    @Test fun aDelegateThatClaimedNothingIsNotNamedAsHavingRunIt() {
        // The other half of #170: `executedBy` named the delegate that went
        // first, whether or not it took anything.
        val (_, by) = executedForAll(
            listOf(claim("TfLiteNnapiDelegate", 0, 1), claim("TfLiteGpuDelegateV2", 1, 1)),
        )

        assertEquals("TfLiteGpuDelegateV2", by)
    }
}
