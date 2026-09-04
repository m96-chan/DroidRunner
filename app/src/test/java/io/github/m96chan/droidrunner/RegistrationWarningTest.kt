package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.model.RunnerTarget
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationWarningTest {

    private val publicRepo = RunnerTarget.Repository("m96-chan", "DroidRunner")

    @Test fun aPrivateRepositoryPassesWithoutInterruption() {
        // The people who can open a pull request against it are people who
        // already have access. Warning here would be the kind of prompt users
        // learn to tap through, which costs the warnings that matter.
        assertNull(registrationWarning(publicRepo, repositoryIsPrivate = true))
    }

    @Test fun aPublicRepositoryNamesWhatCanRunOnThePhone() {
        val warning = registrationWarning(publicRepo, repositoryIsPrivate = false)
        assertNotNull(warning)
        assertTrue("it says which repository", warning!!.headline.contains("m96-chan/DroidRunner"))
        assertTrue("and what a fork can do", warning.detail.contains("fork"))
    }

    @Test fun unknownVisibilityIsTreatedAsPublic() {
        // Being warned about a private repository costs a tap; the reverse
        // costs the device. #58 is the same lesson from the parsing side —
        // a field GitHub did not send must not read as reassurance.
        val warning = registrationWarning(publicRepo, repositoryIsPrivate = null)
        assertNotNull(warning)
        assertTrue(warning!!.headline.contains("did not say"))
    }

    @Test fun anOrganizationIsAlwaysWorthStoppingFor() {
        // Visibility is beside the point: the runner serves every repository in
        // the organization, so the question is not whether one of them is
        // public but how many people can reach the device.
        val warning = registrationWarning(RunnerTarget.Organization("Comic-Market-Kannai"), null)
        assertNotNull(warning)
        assertTrue(warning!!.headline.contains("Comic-Market-Kannai"))
        assertTrue("and points at the way to narrow it", warning.detail.contains("runner group"))
    }

    @Test fun anOrganizationIsNotExcusedByAPrivateRepositoryFlag() {
        // The flag describes a repository and an organization target has none;
        // letting it through would silence the wider warning of the two.
        assertNotNull(
            registrationWarning(RunnerTarget.Organization("Comic-Market-Kannai"), repositoryIsPrivate = true),
        )
    }
}
