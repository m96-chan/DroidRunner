package io.github.m96chan.droidrunner.npu

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sweep, described in one request (issue #94).
 *
 * The unit of work for a compiler is not a model, it is every operator it can
 * emit at every precision it supports — a few hundred single-op files of a few
 * KB each. The payload is trivial and the compute is seconds; the overhead is
 * a workflow step per model, which occupies the fleet for an evening to do a
 * minute of work.
 *
 * Parsing is separate from running because the interesting decisions are here:
 * what a missing field defaults to, and which malformed entry is rejected
 * without taking the rest of the sweep with it.
 */
internal object BatchRequest {

    data class Entry(
        /** The caller's name for this row, echoed back so it can be matched up. */
        val id: String,
        val path: String,
        val device: String? = null,
        /**
         * Zero means load, delegate and allocate but do not time — perhaps
         * half of any sweep only asks "was this accepted", and that answer is
         * complete once tensors are allocated.
         */
        val iterations: Int = DEFAULT_ITERATIONS,
        val inputs: List<String> = emptyList(),
        val outputDir: String? = null,
        /** Why this row cannot be run, when it cannot. */
        val rejection: String? = null,
    )

    const val DEFAULT_ITERATIONS = 50

    /** Default ceiling on the whole sweep, not on any one model. */
    const val DEFAULT_BUDGET_MS = 600_000L

    /** How long the whole sweep may take, from the request or the default. */
    fun budgetMs(request: JSONObject): Long =
        request.optLong("budgetMs", DEFAULT_BUDGET_MS).coerceIn(1_000L, 3_600_000L)

    /**
     * The manifest, in order, with malformed rows carried rather than dropped.
     *
     * A sweep is largely *made of* rejections and each one is the data, so an
     * entry this cannot understand comes back as an entry that says why —
     * never as a shorter array the caller has to diff against what it sent.
     */
    fun entries(request: JSONObject): List<Entry> {
        val models = request.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).map { index ->
            val row = models.optJSONObject(index)
                ?: return@map Entry(
                    id = "$index",
                    path = "",
                    rejection = "entry $index is not an object",
                )
            val id = row.optString("id").ifBlank { "$index" }
            val path = row.optString("path")
            if (path.isBlank()) {
                return@map Entry(id = id, path = "", rejection = "entry $id has no path")
            }
            Entry(
                id = id,
                path = path,
                device = row.optString("device").takeIf { it.isNotBlank() },
                iterations = row.optInt("iterations", DEFAULT_ITERATIONS).coerceIn(0, 500),
                inputs = row.optJSONArray("inputs")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty(),
                outputDir = row.optString("outputDir").takeIf { it.isNotBlank() },
            )
        }
    }

    /** The envelope, whatever happened inside it. */
    fun response(results: List<String>, ranOutOfTime: String?): String = JSONObject()
        .put("schema", ResultContract.SCHEMA)
        .put("ok", true)
        .put("results", JSONArray(results.map { JSONObject(it) }))
        .apply {
            // Naming it is the point: a sweep that stops has met one driver
            // that will not return, and the caller needs to know which.
            ranOutOfTime?.let {
                put("budgetExhausted", true)
                put("stoppedAt", it)
            }
        }
        .toString()

    /** A row that was never attempted, in the same shape as one that was. */
    fun skipped(entry: Entry, reason: String): String = JSONObject()
        .put("schema", ResultContract.SCHEMA)
        .put("ok", false)
        .put("code", ResultContract.Code.FAILED)
        .put("id", entry.id)
        .put("model", entry.path.substringAfterLast('/'))
        .put("error", reason)
        .toString()

    /** Stamps a per-model result with the row it came from. */
    fun identify(result: String, entry: Entry): String =
        runCatching { JSONObject(result).put("id", entry.id).toString() }
            .getOrElse { skipped(entry, "the run produced no readable answer") }
}
