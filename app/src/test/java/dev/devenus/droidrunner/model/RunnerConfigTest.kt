package dev.devenus.droidrunner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerConfigTest {
    @Test fun validRepository() {
        val config = RunnerConfig("m96-chan", "droid-runner", "pixel-01", setOf("android"))
        assertNull(config.validate())
        assertEquals("https://github.com/m96-chan/droid-runner", config.repositoryUrl)
    }

    @Test fun rejectsUrlInOwner() {
        val config = RunnerConfig("https://github.com/m96-chan", "droid-runner", "pixel", emptySet())
        assertEquals("Invalid owner", config.validate())
    }
}
