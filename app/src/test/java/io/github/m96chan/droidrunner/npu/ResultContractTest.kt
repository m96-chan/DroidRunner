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

    @Test fun theCodesAreTheOnesTheDocumentLists() {
        // docs/RESULT-CONTRACT.md is what another repository pins to; a code
        // renamed here and not there is a broken promise.
        assertEquals("invalid-request", ResultContract.Code.INVALID_REQUEST)
        assertEquals("unknown-device", ResultContract.Code.UNKNOWN_DEVICE)
        assertEquals("not-installed", ResultContract.Code.NOT_INSTALLED)
        assertEquals("refused", ResultContract.Code.REFUSED)
        assertEquals("failed", ResultContract.Code.FAILED)
    }
}
