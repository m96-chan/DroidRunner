package io.github.m96chan.droidrunner.model

/**
 * What a runner is registered against. GitHub scopes a self-hosted runner to
 * either one repository or a whole organization; the two differ in the URL
 * `config.sh` is given and in which endpoint issues the registration token.
 */
sealed interface RunnerTarget {
    /** Passed to `config.sh --url`. */
    val url: String

    /** Shown in the UI and stored with the registration. */
    val displayName: String

    data class Repository(val owner: String, val name: String) : RunnerTarget {
        override val url get() = "https://github.com/$owner/$name"
        override val displayName get() = "$owner/$name"
    }

    /**
     * Accepts jobs from every repository in the organization unless the runner
     * is placed in a runner group with an allow-list — a wider trust boundary
     * than repository scope, and one the setup screen calls out.
     */
    data class Organization(val org: String) : RunnerTarget {
        override val url get() = "https://github.com/$org"
        override val displayName get() = org
    }
}

data class RunnerConfig(
    val target: RunnerTarget,
    val runnerName: String,
    val labels: Set<String>,
) {
    val repositoryUrl: String get() = target.url

    fun validate(): String? = when {
        !target.parts().all(NAME::matches) -> "Invalid ${if (target is RunnerTarget.Organization) "organization" else "repository"}"
        runnerName.isBlank() -> "Runner name is required"
        else -> null
    }

    private fun RunnerTarget.parts(): List<String> = when (this) {
        is RunnerTarget.Repository -> listOf(owner, name)
        is RunnerTarget.Organization -> listOf(org)
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
