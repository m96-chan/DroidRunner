package io.github.m96chan.droidrunner.npu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One failure shape, whichever layer produced it (issue #138).
 *
 * The two runners disagreed about which field held what: a class name in
 * `error` and the words in `message` on one path, both glued into `error` on
 * the other, and a `detail` that read as the vendor's and held our loader's.
 * The first consumer outside this project had to read all three and guess.
 */
class FailureShapeTest {

    @Test fun errorIsOursAndMessageIsTheirs() {
        val body = ResultContract.failure(
            code = ResultContract.Code.INVALID_MODEL,
            error = "no interpreter could be built from this file",
            message = "Cannot create interpreter: BytesRequired overflowed. Tensor 0 …",
        )

        assertEquals("no interpreter could be built from this file", body.getString("error"))
        assertTrue(body.getString("message").startsWith("Cannot create interpreter"))
    }

    @Test fun nothingUnderneathMeansNoMessageRatherThanAnEmptyOne() {
        // An empty string reads as "they said nothing quotable"; absent reads
        // as "nobody below us was asked". The second is what is true when a
        // request never reached a layer of its own.
        val body = ResultContract.failure(
            code = ResultContract.Code.UNKNOWN_DEVICE,
            error = "no such accelerator on this phone",
        )

        assertFalse(body.has("message"))
        assertFalse(body.has("at"))
    }

    @Test fun anEmptyMessageIsTreatedAsNoMessage() {
        val body = ResultContract.failure(
            code = ResultContract.Code.FAILED, error = "it failed", message = "  ",
        )

        assertFalse(body.has("message"))
    }

    @Test fun everyFailureCarriesTheSchemaTheCodeAndTheFlag() {
        val body = ResultContract.failure(ResultContract.Code.REFUSED, "took no operators")

        assertEquals(ResultContract.SCHEMA, body.getInt("schema"))
        assertFalse(body.getBoolean("ok"))
        assertEquals("refused", body.getString("code"))
    }

    @Test fun theErrorHelperIsTheSameShapeWithNothingUnderneath() {
        val body = JSONObject(
            ResultContract.error(ResultContract.Code.NOT_INSTALLED, "the runtime is not installed"),
        )

        assertEquals("not-installed", body.getString("code"))
        assertEquals("the runtime is not installed", body.getString("error"))
        assertFalse(body.has("message"))
    }

    @Test fun aSkippedBatchRowIsTheSameShapePlusItsIdentity() {
        val entry = BatchRequest.Entry(id = "conv-int8", path = "/home/runner/conv.tflite")

        val body = JSONObject(BatchRequest.skipped(entry, "the batch ran out of time before this"))

        assertEquals("conv-int8", body.getString("id"))
        assertEquals("conv.tflite", body.getString("model"))
        assertEquals("the batch ran out of time before this", body.getString("error"))
        assertFalse(body.has("message"))
    }

    @Test fun detailIsGone() {
        // It held our loader's last error under a name that read as the
        // vendor's, and was empty for most failures — including the ones where
        // Qualcomm's runtime had plenty to say, because those words arrive
        // through TFLite's exception and not through our loader.
        val body = ResultContract.failure(
            ResultContract.Code.FAILED, "the run failed on the HTP", "Error 6020",
        )

        assertFalse(body.has("detail"))
        assertEquals("Error 6020", body.getString("message"))
    }
}
