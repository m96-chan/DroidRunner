package dev.devenus.droidrunner.model

data class RunnerConfig(
    val owner: String,
    val repository: String,
    val runnerName: String,
    val labels: Set<String>,
    val requireCharging: Boolean = true,
    val minimumBatteryPercent: Int = 30,
    val maximumThermalStatus: Int = 3,
) {
    val repositoryUrl: String get() = "https://github.com/$owner/$repository"

    fun validate(): String? = when {
        !NAME.matches(owner) -> "Invalid owner"
        !NAME.matches(repository) -> "Invalid repository"
        runnerName.isBlank() -> "Runner name is required"
        minimumBatteryPercent !in 0..100 -> "Invalid minimum battery percent"
        else -> null
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
