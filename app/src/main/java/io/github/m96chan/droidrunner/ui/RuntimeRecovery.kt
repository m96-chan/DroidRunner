package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.runner.RunnerState

/**
 * When the setup screen offers to install the runtime, and what follows
 * (issue #46).
 *
 * Installing a runtime and registering a runner are separate operations —
 * only registration touches GitHub — but setup used to bundle them, so a
 * device whose runtime went missing (an install that ran out of space, a
 * dropped download, a deleted `.installed` marker) had no way back: Start
 * needs a runtime, and the register button reads "Registered" and is disabled
 * once the selected target is the one already stored. The install action is
 * therefore offered on its own merits — the runtime is missing and there is
 * something to install — and says nothing about registration.
 */
object RuntimeRecovery {

    /**
     * The action exists whenever the runtime does not. Registration is
     * deliberately not part of this: a device that is already registered is
     * exactly the one that cannot escape without it.
     */
    fun shouldOfferInstall(runtimeInstalled: Boolean, manifestAvailable: Boolean): Boolean =
        !runtimeInstalled && manifestAvailable

    /**
     * Installing replaces the directory the listener runs from, so it waits
     * for the listener to be down — the same reason an update waits. Offered
     * but disabled, rather than hidden, so the screen still says the runtime
     * is missing while the runner is being stopped.
     */
    fun canInstallNow(busy: Boolean, runnerState: RunnerState): Boolean =
        !busy && runnerState == RunnerState.STOPPED

    /**
     * Whether a setup step that has just finished should leave a runner running
     * (issues #46, #150).
     *
     * Two paths end here. A device that had already registered was a working
     * runner until its runtime went missing, and putting one back finishes the
     * repair. A device that has just registered — to its first repository or to
     * a different one — is a runner nobody has started. Neither should need a
     * trip to the dashboard to press Start, and a device that never registered
     * has nothing to run and is left alone.
     *
     * Named for the step and not for a caller. While this was
     * `shouldStartRunnerAfterInstall`, registering was the path that did not
     * call it: switching repositories wrote the registration, ran `config.sh`,
     * and left the device silent — registered, healthy, and not running.
     */
    fun shouldStartRunnerAfterSetup(registered: Boolean, runnerState: RunnerState): Boolean =
        registered && runnerState == RunnerState.STOPPED

    /** Says which of the two situations the user is in, since the fix is the same. */
    fun installLabel(registered: Boolean): String =
        if (registered) "Reinstall runtime" else "Install runtime"
}
