package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.model.RunnerTarget

/**
 * What the user is about to point their phone at, said once, at the moment
 * they point it (issue #64).
 *
 * Everything this project says about safety has lived in the README. Someone
 * who followed the tutorial and tapped through the setup screen has been told
 * none of it — and the sentence they most needed is about the repository they
 * just picked.
 */
internal data class RegistrationWarning(val headline: String, val detail: String)

/**
 * The warning that must be confirmed before registering, or null when there is
 * nothing worth stopping for.
 *
 * Private repositories are the quiet case: the people who can open a pull
 * request against one are already people you have given access to. Public
 * repositories are not, and neither is a repository whose visibility the API
 * did not report — unknown is treated as public, because being warned about a
 * private repository costs a tap and the reverse costs the device.
 */
internal fun registrationWarning(
    target: RunnerTarget,
    repositoryIsPrivate: Boolean?,
): RegistrationWarning? = when {
    target is RunnerTarget.Organization -> RegistrationWarning(
        headline = "Every repository in ${target.org} can use this device",
        detail = "An organization runner accepts jobs from all of them unless you " +
            "put it in a runner group with an allow-list. Anyone who can push a " +
            "workflow to any of those repositories can run code on this phone.",
    )

    repositoryIsPrivate == true -> null

    repositoryIsPrivate == false -> RegistrationWarning(
        headline = "${target.displayName} is public",
        detail = "A pull request from a fork can run its own code on this phone, " +
            "with whatever this device can reach. Only register a public " +
            "repository if you control which workflows run for forks.",
    )

    else -> RegistrationWarning(
        headline = "GitHub did not say whether ${target.displayName} is public",
        detail = "If it is, a pull request from a fork can run its own code on this " +
            "phone. Check the repository's visibility before letting it dispatch " +
            "jobs here.",
    )
}
