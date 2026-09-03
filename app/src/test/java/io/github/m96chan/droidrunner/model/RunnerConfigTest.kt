package io.github.m96chan.droidrunner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerConfigTest {
    private fun config(target: RunnerTarget, name: String = "pixel-01") =
        RunnerConfig(target, name, setOf("android"))

    @Test fun validRepository() {
        val config = config(RunnerTarget.Repository("m96-chan", "droid-runner"))
        assertNull(config.validate())
        assertEquals("https://github.com/m96-chan/droid-runner", config.repositoryUrl)
        assertEquals("m96-chan/droid-runner", config.target.displayName)
    }

    @Test fun validOrganization() {
        val config = config(RunnerTarget.Organization("Comic-Market-Kannai"))
        assertNull(config.validate())
        assertEquals("https://github.com/Comic-Market-Kannai", config.repositoryUrl)
        assertEquals("Comic-Market-Kannai", config.target.displayName)
    }

    @Test fun rejectsUrlInOwner() {
        val config = config(RunnerTarget.Repository("https://github.com/m96-chan", "droid-runner"))
        assertEquals("Invalid repository", config.validate())
    }

    @Test fun rejectsUrlInOrganization() {
        val config = config(RunnerTarget.Organization("https://github.com/evil"))
        assertEquals("Invalid organization", config.validate())
    }

    @Test fun rejectsBlankRunnerName() {
        assertEquals(
            "Runner name is required",
            config(RunnerTarget.Repository("m96-chan", "droid-runner"), name = "").validate(),
        )
    }
}
