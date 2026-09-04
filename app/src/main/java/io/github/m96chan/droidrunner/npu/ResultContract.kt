package io.github.m96chan.droidrunner.npu

import org.json.JSONObject

/**
 * What a consumer outside this repository is allowed to rely on (issue #95).
 *
 * Reading a device result used to mean calling the wrapper, hoping the JSON
 * arrived on stdout by itself, and pulling fields out with `grep` — the
 * wrapper does exactly that to itself. That is fine for a human reading a job
 * log and useless for another project's CI gate, which is the whole reason to
 * point a workflow at a phone.
 *
 * So every response carries a [SCHEMA] and, when something went wrong, a
 * [code] from a closed set. The prose in `error` is for a person and may be
 * reworded at any time; the code is for a program and may not.
 */
internal object ResultContract {

    /**
     * Bumped only for a change a consumer pinned to the old number could not
     * survive. Adding a field is not one of those.
     */
    const val SCHEMA = 1

    /**
     * Why a request did not produce a measurement.
     *
     * Deliberately few and deliberately coarse: a sweep needs to tell apart
     * "record this and carry on" from "stop, the phone is gone", and every
     * further distinction is prose.
     */
    object Code {
        /** The request was malformed, or named a path outside the job's home. */
        const val INVALID_REQUEST = "invalid-request"

        /** No such accelerator, or a backend this device does not have. */
        const val UNKNOWN_DEVICE = "unknown-device"

        /** The vendor runtime this device would need is not installed. */
        const val NOT_INSTALLED = "not-installed"

        /** It ran, but nothing could be attributed to the accelerator asked for. */
        const val REFUSED = "refused"

        /** Anything else that stopped a run. */
        const val FAILED = "failed"
    }

    /** Stamps [body] with the schema, leaving whatever else it says alone. */
    fun stamp(body: String): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
            ?: return JSONObject()
                .put("schema", SCHEMA)
                .put("ok", false)
                .put("code", Code.FAILED)
                .put("error", "the device produced a response that is not JSON")
                .toString()
        if (!parsed.has("schema")) parsed.put("schema", SCHEMA)
        // An error without a code cannot be branched on, and every caller of
        // this has to branch on something.
        if (!parsed.optBoolean("ok", true) && !parsed.has("code")) {
            parsed.put("code", Code.FAILED)
        }
        return parsed.toString()
    }

    /** An error body in the shape the contract promises. */
    fun error(code: String, message: String): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", false)
        .put("code", code)
        .put("error", message)
        .toString()
}
