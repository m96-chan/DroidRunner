package io.github.m96chan.droidrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the proot invocation. Every setting pinned here
 * was found by debugging a real phone, and every one of them can be dropped or
 * reordered without breaking the build, the app, or any other test — the only
 * symptom is that jobs stop running on device. So each test says what breaks,
 * not just what string went missing.
 */
class RunnerCommandTest {

    private val nativeDir = "/data/app/io.github.m96chan.droidrunner-1/lib/arm64"
    private val cacheTmp = "/data/user/0/io.github.m96chan.droidrunner/cache/proot-tmp"
    private val runtimeDir = File("/data/user/0/io.github.m96chan.droidrunner/files/runner-runtime")

    private fun invocation(command: List<String> = listOf("/home/runner/run.sh")) =
        RunnerCommand.prootCommand(nativeDir, cacheTmp, runtimeDir, command)

    /** True when [run] appears as consecutive arguments, i.e. as one option. */
    private fun List<String>.hasRun(vararg run: String): Boolean =
        windowed(run.size).any { it == run.toList() }

    @Test fun prootIsRunFromTheNativeLibraryDirectory() {
        // Android 10+ refuses to exec() a binary out of app data storage, so
        // proot ships as a jniLib and is launched from nativeLibraryDir. Point
        // this anywhere under files/ or cache/ and every job dies with EACCES.
        assertEquals("$nativeDir/libproot.so", invocation().command().first())
    }

    @Test fun theLoadersAreNamedInProotsOwnEnvironment() {
        // proot re-execs itself through these loaders; it reads them from its
        // environment before it starts, so they cannot be passed as guest-side
        // assignments like the runner variables below. The 32-bit one is not
        // spare: it is what any armeabi-v7a helper inside the rootfs lands on.
        val env = invocation().environment()
        assertEquals("$nativeDir/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("$nativeDir/libproot-loader32.so", env["PROOT_LOADER_32"])
    }

    @Test fun prootScratchSpaceStaysInsideTheAppCache() {
        // proot's default scratch directory is the host /tmp, which an Android
        // app cannot write to; without this it fails before the guest starts.
        assertEquals(cacheTmp, invocation().environment()["PROOT_TMP_DIR"])
    }

    @Test fun theGuestIsAllowedToRunAsTheRootProotPretendsItIs() {
        // -0 fakes uid 0 so the rootfs looks writable, and the official runner
        // then refuses to start ("Must not run with sudo") unless it is told
        // that root is intentional. The two belong together: keeping -0 while
        // losing RUNNER_ALLOW_RUNASROOT leaves a runner that never comes up.
        val command = invocation().command()
        assertTrue("proot must fake uid 0", command.contains("-0"))
        assertTrue(
            "the runner refuses to start as root without this",
            command.contains("RUNNER_ALLOW_RUNASROOT=1"),
        )
    }

    @Test fun dotNetsHeapIsCappedSoItsGcCanStart() {
        // The runner is a .NET application, and .NET's GC cannot reserve its
        // default heap inside proot on Android: it aborts at startup with
        // 0x8007000E (out of memory) on a phone with gigabytes free. Capping
        // the hard limit at 768MB is what makes it start at all, so this is a
        // launch requirement and not a memory-tuning nicety.
        assertTrue(
            "without a heap cap the runner dies at startup with 0x8007000E",
            invocation().command().contains("DOTNET_GCHeapHardLimit=0x30000000"),
        )
    }

    @Test fun theGuestGetsTheKernelInterfacesItNeeds() {
        // /dev, /proc and /sys are not part of the downloaded rootfs. Without
        // them the runner cannot read /dev/urandom, .NET cannot count CPUs,
        // and the NNAPI probe cannot see the device at all.
        val command = invocation().command()
        assertTrue(command.hasRun("-b", "/dev"))
        assertTrue(command.hasRun("-b", "/proc"))
        assertTrue(command.hasRun("-b", "/sys"))
    }

    @Test fun theRunnerHomeIsMountedFromTheDownloadedRuntime() {
        // The rootfs is the OS image; the runner's own home — its registration,
        // credentials and _work tree — lives beside it and is bound over /home
        // so that replacing the rootfs on an update does not lose it.
        val command = invocation().command()
        assertTrue(command.hasRun("-r", "${runtimeDir.absolutePath}/rootfs"))
        assertTrue(command.hasRun("-b", "${runtimeDir.absolutePath}/home:/home"))
        assertTrue(
            "config.sh and run.sh are invoked by absolute path but expect this cwd",
            command.hasRun("-w", "/home/runner"),
        )
    }

    @Test fun theGuestDiesWithTheAppThatStartedIt() {
        // --kill-on-exit makes proot tear the whole guest down when it exits.
        // Without it a killed service leaves the listener and any job it was
        // running orphaned, and the next start hits "a session already exists".
        assertTrue(invocation().command().contains("--kill-on-exit"))
    }

    @Test fun theRunnerVariablesAreAssignedForTheGuestCommand() {
        // These reach run.sh as assignments to the guest's own /usr/bin/env,
        // which is why the guest sees a sane HOME, PATH and TMPDIR at all — the
        // host environment an Android service starts with has none of them.
        // Everything after /usr/bin/env and before the command is such an
        // assignment; the command itself comes last. The PROOT_* variables go
        // the other way round: proot reads those from its own environment
        // before it ever execs anything, so they must stay off the argv.
        val command = invocation().command()
        val env = command.indexOf("/usr/bin/env")
        val script = command.indexOf("/home/runner/run.sh")
        assertTrue("the guest command runs through env(1)", env in 0 until script)
        assertEquals("the command being run stays last", command.size - 1, script)
        listOf(
            "HOME=/home/runner",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "RUNNER_ALLOW_RUNASROOT=1",
            "DOTNET_GCHeapHardLimit=0x30000000",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ).forEach {
            assertTrue("$it must be assigned inside the guest", command.indexOf(it) > env)
            assertTrue("$it must be assigned before the command", command.indexOf(it) < script)
        }
        assertTrue(
            "PROOT_* stay on the host: proot reads them, the guest does not",
            command.none { it.startsWith("PROOT_") },
        )
    }

    @Test fun everyProotOptionComesBeforeTheGuestCommand() {
        // proot takes its options up to the program it runs; anything that
        // drifts past /usr/bin/env silently becomes an argument to the runner
        // instead, which ignores it — the mount or the uid is then simply gone.
        val command = invocation(listOf("/home/runner/config.sh", "--unattended")).command()
        val env = command.indexOf("/usr/bin/env")
        listOf("--kill-on-exit", "-0", "-r", "-b", "-w").forEach {
            assertTrue("$it is a proot option", command.indexOf(it) in 0 until env)
        }
        assertEquals(
            "the caller's command is passed through unchanged, and last",
            listOf("/home/runner/config.sh", "--unattended"),
            command.takeLast(2),
        )
    }

    @Test fun theWholeInvocationIsWhatTheDeviceWasDebuggedWith() {
        // The pieces above are also load-bearing as a whole: this is the exact
        // argument list that runs jobs on hardware. A failure here that the
        // other tests do not also report means something was added, removed or
        // reordered — check it on a phone before updating this list.
        assertEquals(
            listOf(
                "$nativeDir/libproot.so",
                "--kill-on-exit", "-0",
                "-r", "${runtimeDir.absolutePath}/rootfs",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${runtimeDir.absolutePath}/home:/home",
                "-w", "/home/runner",
                "/usr/bin/env",
                "HOME=/home/runner",
                "LANG=C.UTF-8",
                "TMPDIR=/tmp",
                "RUNNER_ALLOW_RUNASROOT=1",
                "DOTNET_GCHeapHardLimit=0x30000000",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/home/runner/run.sh",
            ),
            invocation().command(),
        )
    }
}
