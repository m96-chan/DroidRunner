package io.github.m96chan.droidrunner.npu

import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelRunnerTest {

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
}
