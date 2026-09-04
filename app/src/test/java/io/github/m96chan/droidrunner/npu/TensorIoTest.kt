package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TensorIoTest {

    @Rule @JvmField val folder = TemporaryFolder()

    private val images = TensorIo.Spec(
        index = 0,
        name = "images",
        type = "UINT8",
        shape = listOf(1, 224, 224, 3),
        bytes = 150_528,
        scale = 0.00390625f,
        zeroPoint = 0,
    )

    @Test fun noFilesMeansTheOldBehaviourAndNoComplaint() {
        // A latency job passes nothing and must keep working exactly as before.
        assertNull(TensorIo.mismatch(listOf(images), emptyList()))
    }

    @Test fun theWrongNumberOfFilesNamesWhatTheModelWanted() {
        // The caller is a compiler that just emitted this graph; telling it
        // "invalid request" would send it looking in the wrong place.
        val message = TensorIo.mismatch(
            listOf(images, images.copy(index = 1, name = "mask")),
            listOf(folder.newFile("a.bin")),
        )

        assertTrue(message, message!!.contains("2 input tensor(s)"))
        assertTrue(message, message.contains("1 file(s)"))
        assertTrue(message, message.contains("images"))
    }

    @Test fun theWrongSizeSaysWhichTensorAndByHowMuch() {
        // Byte length is the one thing a caller cannot guess from the file: the
        // shape is only final after allocateTensors, and a delegate can change
        // it. So the message carries both numbers.
        val file = folder.newFile("short.bin").apply { writeBytes(ByteArray(10)) }

        val message = TensorIo.mismatch(listOf(images), listOf(file))

        assertTrue(message, message!!.contains("150528 bytes"))
        assertTrue(message, message.contains("short.bin is 10"))
    }

    @Test fun bytesGoInAndComeBackUnchanged() {
        val content = ByteArray(64) { (it * 7 % 251).toByte() }
        val file = folder.newFile("in.bin").apply { writeBytes(content) }
        val buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())

        TensorIo.load(buffer, file)

        val read = ByteArray(64).also { buffer.get(it) }
        assertTrue(content.contentEquals(read))
    }

    @Test fun anOutputIsWrittenWholeAndLeftRewoundForTheNextRun() {
        // The buffer is reused by the timing loop, so saving must not consume
        // it — that is the same class of mistake as the warmup that did not
        // rewind and made every second inference throw.
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
            repeat(8) { put(it.toByte()) }
            rewind()
        }
        val spec = TensorIo.Spec(2, "Softmax", "UINT8", listOf(1, 8), 8)

        val target = TensorIo.Target(folder.root, "/home/runner/_work/out")
        val reported = TensorIo.save(target, spec, buffer)

        assertEquals(8L, folder.root.resolve("output-2.bin").length())
        assertEquals(8, reported.getInt("bytes"))
        assertEquals(2, reported.getInt("index"))
        assertEquals(0, buffer.position())
        // The path a job can actually open, not where this process wrote it:
        // the guest's /home/runner is a bind mount of a directory under
        // /data/data, and reporting the host side hands back something the
        // caller cannot use and discloses the layout for nothing.
        assertEquals("/home/runner/_work/out/output-2.bin", reported.getString("path"))
    }

    @Test fun outputFilesAreNamedByIndexBecauseATensorNameIsTheGraphsToChoose() {
        // Graph authors can call a tensor anything, slashes included; only the
        // index is guaranteed unique and safe to build a path from.
        assertEquals(
            "output-1.bin",
            TensorIo.fileName(TensorIo.Spec(1, "conv/BiasAdd:0", "FLOAT32", listOf(1), 4)),
        )
    }

    @Test fun aQuantizedTensorCarriesItsScaleSoTheCallerNeedNotInferIt() {
        val described = TensorIo.describe(images)

        assertEquals(0.00390625, described.getJSONObject("quantizationParams").getDouble("scale"), 1e-9)
        assertEquals(0, described.getJSONObject("quantizationParams").getInt("zeroPoint"))
        assertEquals(listOf(1, 224, 224, 3), (0 until 4).map { described.getJSONArray("shape").getInt(it) })
    }

    @Test fun aFloatTensorHasNoQuantizationParamsRatherThanZeroedOnes() {
        // TFLite reports scale 0 for an unquantized tensor; passing that on
        // would invite a caller to divide by it.
        val described = TensorIo.describe(
            TensorIo.Spec(0, "images", "FLOAT32", listOf(1, 4), 16, scale = 0f, zeroPoint = 0),
        )

        assertTrue(described.isNull("quantizationParams") || !described.has("quantizationParams"))
    }
}
