package dev.devenus.droidrunner.runner

import dev.devenus.droidrunner.model.RunnerConfig
import java.io.File

object RunnerCommand {
    fun configure(runtimeDir: File, config: RunnerConfig, token: String): List<String> = proot(runtimeDir) + listOf(
        "/home/runner/config.sh", "--unattended", "--replace",
        "--url", config.repositoryUrl,
        "--token", token,
        "--name", config.runnerName,
        "--labels", config.labels.joinToString(","),
        "--work", "_work"
    )

    fun run(runtimeDir: File): List<String> = proot(runtimeDir) + "/home/runner/run.sh"

    private fun proot(dir: File): List<String> = listOf(
        File(dir, "bin/proot").absolutePath,
        "--kill-on-exit", "-0", "-r", File(dir, "rootfs").absolutePath,
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", "${File(dir, "home").absolutePath}:/home",
        "-w", "/home/runner", "/usr/bin/env", "HOME=/home/runner",
        "LANG=C.UTF-8", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    )
}
