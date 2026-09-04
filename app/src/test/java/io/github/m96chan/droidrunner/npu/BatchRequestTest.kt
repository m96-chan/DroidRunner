package io.github.m96chan.droidrunner.npu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRequestTest {

    private fun request(json: String) = JSONObject(json)

    @Test fun aManifestKeepsItsOrderAndItsNames() {
        // The caller matches results back to what it sent by id, so the id it
        // chose has to survive and the order has to be the order.
        val entries = BatchRequest.entries(
            request(
                """{"models":[
                     {"id":"conv-int8","path":"/home/runner/a.tflite"},
                     {"id":"add-fp16","path":"/home/runner/b.tflite","device":"qnn-htp"}
                   ]}""",
            ),
        )

        assertEquals(listOf("conv-int8", "add-fp16"), entries.map { it.id })
        assertEquals("qnn-htp", entries[1].device)
        assertNull(entries[0].device)
    }

    @Test fun aRowWithoutAnIdGetsItsIndexRatherThanBeingDropped() {
        val entries = BatchRequest.entries(
            request("""{"models":[{"path":"/home/runner/a.tflite"}]}"""),
        )

        assertEquals("0", entries.single().id)
    }

    @Test fun aMalformedRowComesBackSayingSoInsteadOfShorteningTheArray() {
        // A sweep is largely made of rejections and each one is data. A caller
        // that sent 300 rows and got 297 back has to diff to find out which.
        val entries = BatchRequest.entries(
            request("""{"models":[{"path":"/home/runner/a.tflite"},{"id":"b"},"nonsense"]}"""),
        )

        assertEquals(3, entries.size)
        assertNull(entries[0].rejection)
        assertTrue(entries[1].rejection!!.contains("no path"))
        assertTrue(entries[2].rejection!!.contains("not an object"))
    }

    @Test fun iterationsZeroSurvivesAsZero() {
        // "was this graph accepted" is complete once tensors are allocated,
        // and half a sweep asks nothing else. Rounding it up to 1 would put
        // the timing loop back and cost the sweep its evening.
        val entries = BatchRequest.entries(
            request("""{"models":[{"path":"/home/runner/a.tflite","iterations":0}]}"""),
        )

        assertEquals(0, entries.single().iterations)
    }

    @Test fun anAbsentIterationsFieldIsNotZero() {
        // Zero has a meaning now, so a caller who said nothing must not get it.
        val entries = BatchRequest.entries(
            request("""{"models":[{"path":"/home/runner/a.tflite"}]}"""),
        )

        assertEquals(BatchRequest.DEFAULT_ITERATIONS, entries.single().iterations)
    }

    @Test fun theBudgetIsBoundedWhateverWasAskedFor() {
        assertEquals(BatchRequest.DEFAULT_BUDGET_MS, BatchRequest.budgetMs(request("{}")))
        assertEquals(5_000L, BatchRequest.budgetMs(request("""{"budgetMs":5000}""")))
        // A budget of zero would make every row a timeout; an unbounded one
        // hands a hung driver the whole job.
        assertEquals(1_000L, BatchRequest.budgetMs(request("""{"budgetMs":0}""")))
        assertEquals(3_600_000L, BatchRequest.budgetMs(request("""{"budgetMs":99999999}""")))
    }

    @Test fun theEnvelopeNamesWhereItStopped() {
        // A sweep that stops has met one driver that will not return, and
        // which one is the only thing the caller can act on.
        val body = JSONObject(
            BatchRequest.response(listOf("""{"ok":true,"id":"a"}"""), ranOutOfTime = "b"),
        )

        assertEquals(1, body.getJSONArray("results").length())
        assertTrue(body.getBoolean("budgetExhausted"))
        assertEquals("b", body.getString("stoppedAt"))
    }

    @Test fun anUneventfulBatchSaysNothingAboutTheBudget() {
        val body = JSONObject(BatchRequest.response(listOf("""{"ok":true}"""), ranOutOfTime = null))

        assertFalse(body.has("budgetExhausted"))
        assertFalse(body.has("stoppedAt"))
    }

    @Test fun everyRowCarriesTheIdItWasSentWith() {
        val entry = BatchRequest.Entry(id = "conv-int8", path = "/home/runner/a.tflite")

        val identified = JSONObject(BatchRequest.identify("""{"ok":true,"avgUs":1.0}""", entry))
        assertEquals("conv-int8", identified.getString("id"))

        // Including one that never ran, so the array is the manifest.
        val skipped = JSONObject(BatchRequest.skipped(entry, "ran out of time"))
        assertEquals("conv-int8", skipped.getString("id"))
        assertFalse(skipped.getBoolean("ok"))
        assertEquals(ResultContract.SCHEMA, skipped.getInt("schema"))
    }

    @Test fun aRunThatAnsweredWithRubbishStillFillsItsRow() {
        val entry = BatchRequest.Entry(id = "x", path = "/home/runner/a.tflite")

        val row = JSONObject(BatchRequest.identify("Segmentation fault", entry))

        assertEquals("x", row.getString("id"))
        assertFalse(row.getBoolean("ok"))
    }
}
