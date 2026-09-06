package io.github.m96chan.droidrunner.npu

/**
 * What a `device` field asks for, once more than one accelerator can be named
 * (issue #159).
 *
 * `--device` has always meant one accelerator, and a caller parsing today's
 * answers should not have that change under them — so a plain name behaves
 * exactly as before and the list form has to be asked for. Nothing here is a
 * new capability of the silicon; it is a way to say something TFLite already
 * supports, which the contract had no words for.
 *
 * Pure, because every rule below is a refusal that has to be identical whether
 * a device is attached or not.
 */
internal object DeviceRequest {

    /** Separates accelerators in the list form. Order is meaningful. */
    const val SEPARATOR = '+'

    /** The opt-in. Absent, the list form is simply not a device name. */
    const val FEATURE = "multi-delegate"

    sealed interface Parsed {
        /** One accelerator, or none — the shape everything before this had. */
        data class Single(val device: String?) : Parsed

        /**
         * Several, in the order given. TFLite offers each delegate what the
         * previous one did not claim, so the order decides the split and
         * cannot be sorted away.
         */
        data class Several(val devices: List<String>) : Parsed

        /** Refused, with the code the contract already defines for it. */
        data class Refused(val code: String, val reason: String) : Parsed
    }

    fun parse(device: String?, features: Set<String>): Parsed {
        val text = device?.takeIf { it.isNotBlank() } ?: return Parsed.Single(null)
        if (!text.contains(SEPARATOR)) return Parsed.Single(text)

        val parts = text.split(SEPARATOR).map(String::trim)
        if (FEATURE !in features) {
            return Parsed.Refused(
                ResultContract.Code.UNKNOWN_DEVICE,
                "'$text' names more than one accelerator; that is experimental and " +
                    "has to be asked for with --feature $FEATURE",
            )
        }
        if (parts.any(String::isBlank)) {
            return Parsed.Refused(
                ResultContract.Code.INVALID_REQUEST,
                "'$text' has an empty accelerator name between separators",
            )
        }
        if (parts.size != parts.distinct().size) {
            // Attaching the same delegate twice claims nothing the first did
            // not, so the second is silently a no-op — an answer that looks
            // like a partition and is not one.
            return Parsed.Refused(
                ResultContract.Code.INVALID_REQUEST,
                "'$text' names the same accelerator twice",
            )
        }
        // Qualcomm's runtime lives in another process, and two delegates need
        // one address space. This is not a gap to fill later: the process split
        // is there because the FSF's line for "one program" is the shared
        // address space and PRoot's GPL-2.0 code is in the main process (#82).
        parts.firstOrNull { QnnBackend.names().contains(it) }?.let { qnn ->
            return Parsed.Refused(
                ResultContract.Code.UNKNOWN_DEVICE,
                "'$qnn' runs in a separate process and cannot share an interpreter " +
                    "with another delegate; on this vendor a crossing is IPC, not memory",
            )
        }
        return Parsed.Several(parts)
    }
}
