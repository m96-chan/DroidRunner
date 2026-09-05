package io.github.m96chan.droidrunner.npu

/**
 * Percentiles over a timing loop (issue #98).
 *
 * min / median / max cannot show a throttle. A run that starts cool and ends
 * hot has a median that barely moves and a tail that doubles, and the tail is
 * the part a regression gate is arguing about — so p90 and p99 are reported
 * beside them.
 *
 * Nearest-rank rather than interpolated: an interpolated p99 of 30 samples is
 * a number no iteration produced, and these are latencies a caller may want to
 * point at. Every value published here was measured.
 */
internal object Timings {

    /**
     * The [percentile]th value of [sorted], which must already be ascending.
     *
     * Nearest rank: ceil(p/100 × n), which puts p100 on the maximum and p0 on
     * the minimum, and never indexes past either end.
     */
    fun percentile(sorted: LongArray, percentile: Int): Long {
        if (sorted.isEmpty()) return 0
        val rank = Math.ceil(percentile.coerceIn(0, 100) / 100.0 * sorted.size).toInt()
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }
}
