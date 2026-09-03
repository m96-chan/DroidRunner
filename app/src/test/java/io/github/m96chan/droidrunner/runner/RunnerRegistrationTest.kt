package io.github.m96chan.droidrunner.runner

import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunnerRegistrationTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun installingARuntimeKeepsWhatTheDeviceRegisteredAs() {
        // Installing replaces the whole runtime directory (issue #46); the
        // details have to survive it or the device silently stops being a
        // runner it was registered as.
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")
        val config = RunnerConfig(
            RunnerTarget.Repository("m96-chan", "DroidRunner"),
            "android-test-abc123",
            setOf("self-hosted", "android"),
        )
        RunnerRegistration.save(old, config)

        RunnerRegistration.copyDetails(old, fresh)

        assertTrue(RunnerRegistration.isConfigured(fresh))
        assertEquals(config, RunnerRegistration.load(fresh))
    }

    @Test fun theRunnersOwnIdentityIsLeftWithTheRuntimeItBelongsTo() {
        // .runner and .credentials are written by the runtime being replaced;
        // the service registers again from the copied details instead.
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")
        File(old, "home/runner").mkdirs()
        File(old, "home/runner/.runner").writeText("{}")
        File(old, "home/runner/.credentials").writeText("{}")
        RunnerRegistration.save(old, RunnerConfig(RunnerTarget.Organization("m96-chan"), "android-test", emptySet()))

        RunnerRegistration.copyDetails(old, fresh)

        assertFalse(RunnerRegistration.isRegistered(fresh))
    }

    @Test fun anUnregisteredDeviceCarriesNothingForward() {
        val old = temp.newFolder("runner-runtime")
        val fresh = temp.newFolder("runner-runtime.new")

        RunnerRegistration.copyDetails(old, fresh)

        assertFalse(RunnerRegistration.isConfigured(fresh))
    }
}
