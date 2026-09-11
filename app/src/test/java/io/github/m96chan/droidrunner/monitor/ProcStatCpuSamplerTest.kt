package io.github.m96chan.droidrunner.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #195: /proc/stat lists only the CPUs that are online, so the rows move
 * under anything that reads them by position.
 *
 * The two bodies below are the shape an SM8650 under core control produces:
 * eight cores present, seven rows in each sample, and a different seven. cpu7
 * is parked for the first and cpu4 for the second, so the row *count* never
 * changes — which is exactly why the old size check did not catch it.
 */
class ProcStatCpuSamplerTest {

    /** cpu7 parked. Fields: user nice system idle iowait irq softirq steal ... */
    private val parkedSeven = """
        cpu  25800 400 8650 203000 1300 200 115 0 0 0
        cpu0 4000 100 1500 20000 200 50 30 0 0 0
        cpu1 4100 100 1500 20000 200 50 30 0 0 0
        cpu2 3900 100 1400 21000 200 50 30 0 0 0
        cpu3 3800 100 1400 21000 200 50 30 0 0 0
        cpu4 1000 0 300 30000 100 10 5 0 0 0
        cpu5 1100 0 300 30000 100 10 5 0 0 0
        cpu6 900 0 250 31000 100 10 5 0 0 0
        intr 123456789 0 0 0
        ctxt 987654321
    """.trimIndent()

    /**
     * 150 ticks later: cpu4 has parked and cpu7 has unparked. cpu3 and cpu6
     * spent the whole interval busy, cpu5 almost none of it.
     */
    private val parkedFour = """
        cpu  26200 400 8900 204000 1300 200 115 0 0 0
        cpu0 4030 100 1520 20100 200 50 30 0 0 0
        cpu1 4115 100 1505 20130 200 50 30 0 0 0
        cpu2 3900 100 1400 21150 200 50 30 0 0 0
        cpu3 3900 100 1450 21000 200 50 30 0 0 0
        cpu5 1100 0 305 30145 100 10 5 0 0 0
        cpu6 1000 0 300 31000 100 10 5 0 0 0
        cpu7 5000 0 400 40000 100 10 5 0 0 0
        intr 123999999 0 0 0
        ctxt 988888888
    """.trimIndent()

    @Test fun eachCoreIsMeasuredAgainstItsOwnPreviousCounters() {
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        val usage = sampler.sample(parkedFour)

        // Every figure below is that core's own two rows and nothing else.
        assertClose(50f / 150, usage[0])  // 30 user + 20 system of 150 ticks
        assertClose(20f / 150, usage[1])
        assertClose(0f, usage[2])         // the whole interval in idle
        assertClose(1f, usage[3])         // and none of it
        assertClose(5f / 150, usage[5])
        assertClose(1f, usage[6])
    }

    @Test fun aCoreThatWasParkedForEitherSampleReportsNothing() {
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        val usage = sampler.sample(parkedFour)

        // cpu4 was online for the first sample only, cpu7 for the second only.
        // Neither has an interval, and the dashboard must be told that rather
        // than handed a number: a parked core drawn at 0% reads as idle.
        assertFalse("cpu4 parked in the second sample", usage.containsKey(4))
        assertFalse("cpu7 parked in the first sample", usage.containsKey(7))
        assertEquals(setOf(0, 1, 2, 3, 5, 6), usage.keys)
    }

    @Test fun noCoreReportsANumberDerivedFromAnotherCore() {
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        val usage = sampler.sample(parkedFour)

        // These are the three numbers reading the rows by position produced,
        // worked through by hand on the bodies above. With cpu4 gone from the
        // second body the rows shift up by one from there:
        //   row 4 is cpu5 over cpu4  -> 42% credited to a core that is parked,
        //   row 5 is cpu6 over cpu5  -> a negative delta, so cpu5 was drawn
        //                               idle while cpu6 ran flat out,
        //   row 6 is cpu7 over cpu6  -> 32% credited to a core at 100%.
        // Not one of them may come back against the core it was filed under.
        assertFalse("nothing is known about a parked cpu4", usage.containsKey(4))
        assertClose(5f / 150, usage.getValue(5))
        assertTrue("cpu5 is not idle", usage.getValue(5) > 0f)
        assertClose(1f, usage.getValue(6))
    }

    @Test fun aCoreComingBackOnlineWaitsForItsOwnBaseline() {
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        sampler.sample(parkedFour)

        // cpu4 unparks. Its counters survived the park, but the interval they
        // span did not, so the first sample after it returns still has nothing
        // to say — the number would cover an unknown stretch of wall clock.
        val returning = parkedFour.replace(
            "cpu5 1100 0 305 30145 100 10 5 0 0 0",
            "cpu4 1000 0 300 30000 100 10 5 0 0 0\ncpu5 1100 0 310 30290 100 10 5 0 0 0",
        )
        assertFalse(sampler.sample(returning).containsKey(4))

        // The sample after that has both ends of an interval and reports.
        val settled = returning.replace(
            "cpu4 1000 0 300 30000 100 10 5 0 0 0",
            "cpu4 1075 0 375 30000 100 10 5 0 0 0",
        )
        assertClose(1f, sampler.sample(settled).getValue(4))
    }

    @Test fun theFirstSampleOfAllHasNothingToSubtractFrom() {
        assertEquals(emptyMap<Int, Float>(), ProcStatCpuSampler().sample(parkedSeven))
    }

    @Test fun countersThatDidNotMoveAreNotAnIdleCore() {
        // Polled faster than the tick, or read twice across a hotplug that
        // froze the counters: there is no interval, so there is no answer.
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        assertEquals(emptyMap<Int, Float>(), sampler.sample(parkedSeven))
    }

    @Test fun theAggregateCpuLineIsNotACore() {
        // `cpu ` with no number is the sum over all of them; counting it as a
        // core would add a ninth row to an eight-core phone.
        val sampler = ProcStatCpuSampler()
        sampler.sample(parkedSeven)
        assertEquals(6, sampler.sample(parkedFour).size)
    }

    @Test fun everyCoreOnlineIsTheOrdinaryCase() {
        val before = (0..7).joinToString("\n") { "cpu$it 1000 0 500 30000 0 0 0 0 0 0" }
        val after = (0..7).joinToString("\n") { "cpu$it 1075 0 500 30075 0 0 0 0 0 0" }
        val sampler = ProcStatCpuSampler()
        sampler.sample(before)
        val usage = sampler.sample(after)

        assertEquals((0..7).toSet(), usage.keys)
        usage.values.forEach { assertClose(0.5f, it) }
    }

    @Test fun theCoreListComesFromWhatTheHardwareHas() {
        // /sys/devices/system/cpu/present, which counts the parked cores too.
        assertEquals((0..7).toList(), parseCpuList("0-7\n"))
        assertEquals(listOf(0, 1, 2, 3, 5, 7), parseCpuList("0-3,5,7"))
        assertEquals(listOf(0), parseCpuList("0"))
    }

    @Test fun anUnreadablePresentFileIsNotAPhoneWithoutCores() {
        // The caller falls back on an empty list; a wrong list would be worse.
        assertEquals(emptyList<Int>(), parseCpuList(null))
        assertEquals(emptyList<Int>(), parseCpuList(""))
        assertEquals(emptyList<Int>(), parseCpuList("not a cpulist"))
    }

    private fun assertClose(expected: Float, actual: Float?) {
        assertEquals(expected.toDouble(), (actual ?: Float.NaN).toDouble(), 0.0001)
    }
}
