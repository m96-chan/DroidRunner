package io.github.m96chan.droidrunner.npu

/**
 * The Qualcomm terms someone has to accept before this device fetches an NPU
 * runtime (issue #82, stage 3).
 *
 * These licences carry field-of-use prohibitions that bind whoever runs the
 * code, not whoever wrote this app. They are not ours to accept on a user's
 * behalf, so nothing is fetched until an acceptance has been recorded here.
 *
 * The two artifacts are covered by **different** licences from **different**
 * Qualcomm entities, so both have to be shown. The POM of each declares a URL
 * for the text, but that URL redirects to an endpoint that answers 403 without
 * credentials; the only copy anyone can actually read is the `LICENSE.pdf`
 * inside the published package. Fetching that one file is therefore part of
 * asking, not part of installing — Qualcomm's own wording ("if you do not
 * agree you must discontinue the download process") assumes you were shown the
 * terms first, and nobody can consent to a document they were never given.
 */
internal object QnnLicences {

    data class Licence(
        val module: QnnArtifacts.Module,
        /** The title on the document itself, which is not what the POM claims. */
        val title: String,
        val licensor: String,
        val sha256: String,
        val bytes: Long,
    ) {
        /** Where the text sits inside the package: the root, not `jni/`. */
        val zipEntry: String get() = "LICENSE.pdf"

        /** Kept apart on disk, because the two documents differ. */
        val fileName: String get() = "${module.artifact}-LICENSE.pdf"
    }

    /**
     * Every licence that has to be accepted.
     *
     * `qnn-runtime` ships the AI Stack License from Qualcomm Technologies;
     * `qnn-litert-delegate` ships the AI Model Hub License from Qualcomm
     * Innovation Center. Both POMs instead declare "Qualcomm AI Hub Model
     * License" for both artifacts, which matches neither document exactly. The
     * bundled text is what a user would be held to, so that is what is shown.
     */
    val required = listOf(
        Licence(
            module = QnnArtifacts.Module.RUNTIME,
            title = "AI Stack License",
            licensor = "Qualcomm Technologies, Inc.",
            sha256 = "ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b",
            bytes = 147_577,
        ),
        Licence(
            module = QnnArtifacts.Module.DELEGATE,
            title = "AI Model Hub License",
            licensor = "Qualcomm Innovation Center, Inc.",
            sha256 = "2d20d88391972aa355c2d2ac0d03e7d4805b9a1c2398649d861c7dfd10dd5f83",
            bytes = 171_533,
        ),
    )

    /**
     * What the restrictions are about, for the screen that asks.
     *
     * Deliberately a map of the document rather than a summary of it: saying
     * "you may not use this for predictive policing" in our own words would be
     * this project paraphrasing someone else's legal terms, and a user acting
     * on the paraphrase would be acting on the wrong text. These point at the
     * sections to read; the document itself is one tap away.
     */
    val obligations = listOf(
        "Prohibited uses. Both licences forbid a list of applications outright, " +
            "among them social scoring, predictive policing, emotion recognition " +
            "in workplaces and schools, and untargeted scraping of images to build " +
            "facial recognition databases.",
        "High-risk uses. A further list — biometric identification, critical " +
            "infrastructure, education, employment, law enforcement, migration, " +
            "administration of justice and others — which Qualcomm advises " +
            "against rather than forbids.",
        "Export and sanctions law. Compliance is yours, including obtaining any " +
            "licences your country requires.",
        "No warranty, and no liability. The software is provided as is.",
    )

    /**
     * Identifies exactly what was accepted.
     *
     * Derived from the documents' own digests and nothing else: a new QNN
     * release that carries the same terms must not force anyone to agree again,
     * and a release that quietly changes them must not inherit an acceptance
     * given for the old text.
     */
    fun fingerprint(): String =
        required.sortedBy { it.module.artifact }.joinToString(" ") { it.sha256 }

    /** Whether [record], as stored on this device, covers what is required now. */
    fun accepted(record: String?): Boolean = record != null && record == fingerprint()
}
