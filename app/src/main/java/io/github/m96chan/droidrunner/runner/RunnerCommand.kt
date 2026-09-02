package io.github.m96chan.droidrunner.runner

import android.content.Context
import io.github.m96chan.droidrunner.model.RunnerConfig
import java.io.File

/**
 * Builds proot invocations for the official runner. proot and its loaders are
 * shipped inside the APK as jniLibs and executed from nativeLibraryDir,
 * because Android 10+ refuses to exec() binaries stored in app data. The
 * downloaded runtime bundle contains only data (rootfs + runner home).
 */
object RunnerCommand {
    fun configure(
        context: Context,
        runtimeDir: File,
        config: RunnerConfig,
        token: String,
        ephemeral: Boolean = false,
    ): ProcessBuilder =
        proot(
            context, runtimeDir,
            listOf(
                "/home/runner/config.sh", "--unattended", "--replace",
                "--url", config.repositoryUrl,
                "--token", token,
                "--name", config.runnerName,
                "--labels", config.labels.joinToString(","),
                "--work", "_work",
            ) + if (ephemeral) listOf("--ephemeral") else emptyList(),
        )

    fun run(context: Context, runtimeDir: File, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder =
        proot(context, runtimeDir, listOf("/home/runner/run.sh")).also {
            it.environment().putAll(extraEnv)
        }

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
                // proot -0 fakes uid 0; the official runner refuses root
                // unless this is set.
                "RUNNER_ALLOW_RUNASROOT=1",
                // .NET's GC cannot reserve its default heap inside proot on
                // Android (0x8007000E); cap it at 768MB.
                "DOTNET_GCHeapHardLimit=0x30000000",
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
