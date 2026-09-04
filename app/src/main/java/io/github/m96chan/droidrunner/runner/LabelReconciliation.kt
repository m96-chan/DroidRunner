package io.github.m96chan.droidrunner.runner

/**
 * Keeping a runner's labels honest after the app that registered it has
 * changed (issue #80).
 *
 * Labels are written once, at registration, and handed to `config.sh`. Nothing
 * has recomputed them since — so a device announces what an old build believed
 * about it. One phone in the fleet is a Snapdragon 8 Gen 3 still labelled
 * `android-no-npu`, from before the SoC matching was fixed in #23, and it is
 * therefore skipped by every workflow selecting on `android-npu`.
 */
internal object LabelReconciliation {

    /**
     * Labels GitHub assigns itself. They are returned alongside the custom ones
     * and must not be sent back as custom, or the runner ends up carrying two
     * of each.
     */
    private val GITHUB_OWNED = setOf("self-hosted", "linux", "windows", "macos", "x64", "arm", "arm64")

    /**
     * Whether what the device would say now differs from what it is saying.
     *
     * Compared as sets: GitHub returns labels in its own order, and a
     * re-registration that changed nothing should not look like a change.
     */
    fun needsUpdate(current: Set<String>, registered: Set<String>): Boolean =
        custom(current) != custom(registered)

    /**
     * What to send as the runner's custom labels.
     *
     * GitHub's replace endpoint takes the whole set, so this is the answer
     * rather than a diff — and it drops anything GitHub owns, which cannot be
     * set and would be rejected or duplicated.
     */
    fun payload(current: Set<String>): List<String> = custom(current).sorted()

    private fun custom(labels: Set<String>): Set<String> =
        labels.filterNot { it.lowercase() in GITHUB_OWNED }.toSet()
}
