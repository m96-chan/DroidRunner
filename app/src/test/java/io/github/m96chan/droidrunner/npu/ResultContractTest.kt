package io.github.m96chan.droidrunner.npu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultContractTest {

    @Test fun everyResponseCarriesTheSchemaItPromises() {
        val stamped = JSONObject(ResultContract.stamp("""{"ok":true,"avgUs":1.5}"""))

        assertEquals(ResultContract.SCHEMA, stamped.getInt("schema"))
        assertEquals(1.5, stamped.getDouble("avgUs"), 1e-9)
    }

    @Test fun aRunnerThatSetItsOwnSchemaKeepsIt() {
        // A batch entry may be stamped before it is nested (#94); stamping the
        // envelope again must not rewrite what is inside it.
        val stamped = JSONObject(ResultContract.stamp("""{"schema":99,"ok":true}"""))

        assertEquals(99, stamped.getInt("schema"))
    }

    @Test fun anErrorWithoutACodeGetsOneRatherThanNone() {
        // Every consumer of this has to branch on something, and the prose in
        // `error` is reworded without a schema bump.
        val stamped = JSONObject(
            ResultContract.stamp("""{"ok":false,"error":"something went wrong"}"""),
        )

        assertEquals(ResultContract.Code.FAILED, stamped.getString("code"))
    }

    @Test fun aCodeTheRunnerChoseIsNotOverwritten() {
        // "refused" is the one a sweep records and carries on from; collapsing
        // it into "failed" would make it indistinguishable from a phone that
        // stopped answering.
        val stamped = JSONObject(
            ResultContract.stamp("""{"ok":false,"code":"refused","error":"took no operators"}"""),
        )

        assertEquals(ResultContract.Code.REFUSED, stamped.getString("code"))
    }

    @Test fun somethingThatIsNotJsonBecomesAnErrorInTheRightShape() {
        // The far side can die mid-sentence; what arrives is whatever was in
        // the pipe, and a consumer should still find schema, ok and code.
        val stamped = JSONObject(ResultContract.stamp("Segmentation fault"))

        assertEquals(ResultContract.SCHEMA, stamped.getInt("schema"))
        assertFalse(stamped.getBoolean("ok"))
        assertEquals(ResultContract.Code.FAILED, stamped.getString("code"))
        assertTrue(stamped.getString("error").isNotBlank())
    }

    @Test fun anErrorBodyHasEverythingTheContractPromises() {
        val body = JSONObject(
            ResultContract.error(ResultContract.Code.UNKNOWN_DEVICE, "no such accelerator"),
        )

        assertEquals(ResultContract.SCHEMA, body.getInt("schema"))
        assertFalse(body.getBoolean("ok"))
        assertEquals("unknown-device", body.getString("code"))
        assertEquals("no such accelerator", body.getString("error"))
    }

    @Test fun aBrokenFileIsNotAStatementAboutADriver() {
        // The first outside consumer sent a model with -1 in a tensor shape.
        // No interpreter could build it, on any device, with or without an
        // accelerator — and it arrived as a bare `failed`, which is the code a
        // sweep records and carries on from. It will fail every remaining row
        // identically, so it has its own.
        val stamped = JSONObject(
            ResultContract.stamp(
                """{"ok":false,"code":"invalid-model",""" +
                    """"error":"Cannot create interpreter: BytesRequired overflowed"}""",
            ),
        )

        assertEquals(ResultContract.Code.INVALID_MODEL, stamped.getString("code"))
    }

    @Test fun aNestedRowCarriesTheContractAndNotJustTheEnvelopeAroundIt() {
        // `stamp` ran once, on the envelope, so a pinned consumer validating
        // the rows inside a sweep found no `schema` on any of them — while the
        // object around them had one (#200).
        val entry = BatchRequest.Entry(id = "conv-int8", path = "/home/runner/conv-int8.tflite")

        val row = JSONObject(BatchRequest.identify("""{"ok":true,"executed":"accelerator"}""", entry))

        assertEquals(ResultContract.SCHEMA, row.getInt("schema"))
        assertEquals("conv-int8", row.getString("id"))
    }

    @Test fun aNestedFailureThatArrivedWithoutACodeIsGivenOne() {
        // A sweep branches on `code` to decide whether to abort, and the
        // contract says it is present on every failure. A row built by hand
        // below must not be able to take it away.
        val entry = BatchRequest.Entry(id = "conv-int8", path = "/home/runner/conv-int8.tflite")

        val row = JSONObject(
            BatchRequest.identify("""{"ok":false,"error":"the runtime is not installed"}""", entry),
        )

        assertEquals(ResultContract.SCHEMA, row.getInt("schema"))
        assertEquals(ResultContract.Code.FAILED, row.getString("code"))
    }

    @Test fun theMissingQnnRuntimeNamesItsCodeWhereThatRowIsBuilt() {
        // On a Qualcomm phone with no runtime installed, every row of a sweep
        // comes from one lambda in RunnerService, and it returned a hand-built
        // object with no `code` at all — the failure #200 opens with. A JVM
        // test cannot start a Service, so this is a source-level canary in the
        // spirit of tools/check-executed-everywhere.sh: it cannot prove the
        // row is right, only that nobody went back to building it by hand.
        val source = sourceOf("runner/RunnerService.kt")
        val lambda = source.substringAfter("private fun qnnModelRunner()")

        assertTrue(
            "the not-installed row must come from ResultContract.failure",
            lambda.contains("ResultContract.failure("),
        )
        assertTrue(
            "and it must carry the code a sweep branches on",
            lambda.contains("Code.NOT_INSTALLED"),
        )
    }

    /** The file as it is on disk, wherever the test happens to be run from. */
    private fun sourceOf(relative: String): String {
        var directory: java.io.File? = java.io.File("").absoluteFile
        while (directory != null) {
            val candidate = java.io.File(
                directory,
                "app/src/main/java/io/github/m96chan/droidrunner/$relative",
            )
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        throw AssertionError("cannot find $relative from ${java.io.File("").absolutePath}")
    }

    @Test fun theCodesAreTheOnesTheDocumentLists() {
        // docs/RESULT-CONTRACT.md is what another repository pins to; a code
        // renamed here and not there is a broken promise.
        assertEquals("invalid-request", ResultContract.Code.INVALID_REQUEST)
        assertEquals("unknown-device", ResultContract.Code.UNKNOWN_DEVICE)
        assertEquals("not-installed", ResultContract.Code.NOT_INSTALLED)
        assertEquals("refused", ResultContract.Code.REFUSED)
        assertEquals("invalid-model", ResultContract.Code.INVALID_MODEL)
        assertEquals("failed", ResultContract.Code.FAILED)
    }
}
