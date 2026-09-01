package dev.devenus.droidrunner.runner

import android.content.Context
import dev.devenus.droidrunner.model.RunnerConfig
import java.io.File

/**
 * Builds proot invocations for the official runner. proot and its loaders are
 * shipped inside the APK as jniLibs and executed from nativeLibraryDir,
 * because Android 10+ refuses to exec() binaries stored in app data. The
 * downloaded runtime bundle contains only data (rootfs + runner home).
 */
object RunnerCommand {
    fun configure(context: Context, runtimeDir: File, config: RunnerConfig, token: String): ProcessBuilder =
        proot(
            context, runtimeDir,
            listOf(
                "/home/runner/config.sh", "--unattended", "--replace",
                "--url", config.repositoryUrl,
                "--token", token,
                "--name", config.runnerName,
                "--labels", config.labels.joinToString(","),
                "--work", "_work",
            ),
        )

    fun run(context: Context, runtimeDir: File): ProcessBuilder =
        proot(context, runtimeDir, listOf("/home/runner/run.sh"))

    private fun proot(context: Context, dir: File, command: List<String>): ProcessBuilder {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val builder = ProcessBuilder(
            listOf(
                "$nativeDir/libproot.so",
                "--kill-on-exit", "-0",
                "-r", File(dir, "rootfs").absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${File(dir, "home").absolutePath}:/home",
                "-w", "/home/runner",
                "/usr/bin/env",
                "HOME=/home/runner",
                "LANG=C.UTF-8",
                "TMPDIR=/tmp",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            ) + command,
        )
        builder.environment()["PROOT_LOADER"] = "$nativeDir/libproot-loader.so"
        builder.environment()["PROOT_LOADER_32"] = "$nativeDir/libproot-loader32.so"
        builder.environment()["PROOT_TMP_DIR"] =
            File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        return builder
    }
}
