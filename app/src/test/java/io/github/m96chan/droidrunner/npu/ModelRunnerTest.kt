package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.qnn.QnnModelRunner
import java.io.File
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRunnerTest {

    @Rule @JvmField val folder = TemporaryFolder()

    private fun buffers(): Pair<Array<Any>, Map<Int, ByteBuffer>> =
        arrayOf<Any>(ByteBuffer.allocate(16)) to mapOf(0 to ByteBuffer.allocate(8))

    /**
     * Stands in for the interpreter: it writes a whole tensor, not "whatever
     * is left". That distinction is the bug — a buffer with nothing remaining
     * does not quietly accept a short write, it throws.
     */
    private fun interpret(inputs: Array<Any>, outputs: Map<Int, ByteBuffer>) {
        inputs.forEach { buffer -> (buffer as ByteBuffer).position(buffer.capacity()) }
        outputs.values.forEach { out -> out.put(ByteArray(out.capacity())) }
    }

    @Test fun reusingBuffersWithoutRewindingThemThrowsOnTheSecondRun() {
        // The failure this guards against, reproduced without a device: the
        // first run consumes every buffer, and the second finds nothing
        // remaining. It cost this feature months, because the exception blames
        // ByteBuffer and appears on every driver at once.
        val (inputs, outputs) = buffers()
        interpret(inputs, outputs)
        assertThrows(BufferOverflowException::class.java) { interpret(inputs, outputs) }
    }

    @Test fun rewindingBeforeEachRunMakesThemRepeatable() {
        val (inputs, outputs) = buffers()
        repeat(5) {
            ModelRunner.rewindAll(inputs, outputs)
            interpret(inputs, outputs)
        }
    }

    @Test fun everyBufferGoesBackToTheStart() {
        val (inputs, outputs) = buffers()
        interpret(inputs, outputs)
        ModelRunner.rewindAll(inputs, outputs)
        assertEquals(0, (inputs[0] as ByteBuffer).position())
        assertEquals(0, outputs.getValue(0).position())
    }

    /**
     * A graph that writes when it is run, and an output buffer that starts as
     * every output buffer starts: allocated, and therefore zero.
     *
     * The distinction is the whole of #191. A zero-filled buffer and a tensor
     * of zeros a graph really computed are the same bytes on disk, so the only
     * way to tell them apart is to know whether anything ran.
     */
    private class Graph {
        val output: ByteBuffer = ByteBuffer.allocate(8)
        var invocations = 0
            private set

        fun invoke() {
            output.rewind()
            output.put(ByteArray(output.capacity()) { 0x2A })
            invocations++
        }
    }

    /** The TFLite runner's invocation sequence, with [Graph] for a driver. */
    private fun tflite(runs: Int, savingOutputs: Boolean): Graph = Graph().also { graph ->
        if (runs > 0) repeat(WARMUP) { graph.invoke() }
        repeat(runs) { graph.invoke() }
        ModelRunner.invokeForOutputs(runs, savingOutputs) { graph.invoke() }
    }

    /**
     * And the Qualcomm runner's. The policy is duplicated there because the
     * two files may not be linked, so it is asserted twice: the copies
     * drifting apart is exactly the failure that duplication invites.
     */
    private fun qnn(runs: Int, savingOutputs: Boolean): Graph = Graph().also { graph ->
        if (runs > 0) repeat(WARMUP) { graph.invoke() }
        repeat(runs) { graph.invoke() }
        QnnModelRunner.invokeForOutputs(runs, savingOutputs) { graph.invoke() }
    }

    @Test fun zeroIterationsWithSomewhereToWriteStillRunsTheGraph() {
        // The defect, on both paths: `{"outputDir": "…", "iterations": 0}`
        // came back ok, naming a file of the right length that no graph had
        // ever written a byte of (#191).
        assertEquals(1, tflite(runs = 0, savingOutputs = true).invocations)
        assertEquals(1, qnn(runs = 0, savingOutputs = true).invocations)
    }

    @Test fun zeroIterationsWithNowhereToWriteStillRunsNothing() {
        // The documented meaning of `iterations: 0` is intact: load, delegate,
        // allocate, do not time (#94). Half a sweep asks nothing else, and
        // running the graph for those rows is the cost that feature exists to
        // avoid.
        assertEquals(0, tflite(runs = 0, savingOutputs = false).invocations)
        assertEquals(0, qnn(runs = 0, savingOutputs = false).invocations)
    }

    @Test fun aTimedRunIsNotRunOneMoreTimeForItsOutputs() {
        // Two warmups and three measured runs, and nothing after them: the
        // timing loop has already left the buffers holding a real answer, and
        // an extra invocation would be a fourth run the caller did not ask for
        // and would not see in `iterations`.
        assertEquals(WARMUP + 3, tflite(runs = 3, savingOutputs = true).invocations)
        assertEquals(WARMUP + 3, qnn(runs = 3, savingOutputs = true).invocations)
    }

    @Test fun nothingReachesDiskThatNoInvocationProduced() {
        // What the consumer sees. `outputFiles` is compared against a golden
        // (#92), so a file of fabricated zeros is indistinguishable from a
        // model that computed the wrong answer — the one thing this feature
        // exists to tell apart.
        listOf(tflite(runs = 0, savingOutputs = true), qnn(runs = 0, savingOutputs = true))
            .forEachIndexed { path, graph ->
                val target = TensorIo.Target(folder.newFolder("out-$path"), "/home/runner/out")
                val spec = TensorIo.Spec(
                    index = 0,
                    name = "Softmax",
                    type = "UINT8",
                    shape = listOf(1, 8),
                    bytes = 8,
                )

                val reported = TensorIo.save(target, spec, graph.output)

                val written = File(target.directory, TensorIo.fileName(spec)).readBytes()
                assertEquals(8, reported.getInt("bytes"))
                assertEquals(8, written.size)
                assertFalse(
                    "the saved tensor is all zeros, so no invocation produced it",
                    written.all { it == 0.toByte() },
                )
            }
    }

    @Test fun bothRunnersAnswerTheSameWay() {
        // The copies are four lines each and cannot be shared across the
        // licensing boundary, so the grid is walked rather than trusted.
        listOf(0, 1, 2, 50).forEach { runs ->
            listOf(true, false).forEach { saving ->
                assertEquals(
                    "runs=$runs savingOutputs=$saving",
                    tflite(runs, saving).invocations,
                    qnn(runs, saving).invocations,
                )
            }
        }
        assertTrue(ModelRunner.invokeForOutputs(0, savingOutputs = true) {})
        assertTrue(QnnModelRunner.invokeForOutputs(0, savingOutputs = true) {})
        assertFalse(ModelRunner.invokeForOutputs(1, savingOutputs = true) {})
        assertFalse(QnnModelRunner.invokeForOutputs(1, savingOutputs = true) {})
    }

    private companion object {
        /** What both runners default to, and what neither warms for nothing. */
        const val WARMUP = 2
    }
}
