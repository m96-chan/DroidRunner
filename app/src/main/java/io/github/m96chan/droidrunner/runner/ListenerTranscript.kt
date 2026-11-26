package io.github.m96chan.droidrunner.runner

/**
 * What one `run.sh` process said about its registration, kept by the thread
 * that reads its output.
 *
 * The exit code cannot say it. `run.sh` exits 0 after a finished job and after
 * "terminated error, no retry needed" alike, so a listener whose registration
 * GitHub had already deleted looked exactly like one that had served its job.
 * One instance per process: a second launch starts with no evidence from the
 * first.
 */
class ListenerTranscript {

    /** A job in this process reached its result line, whatever the result. */
    var jobCompleted: Boolean = false
        private set

    /**
     * GitHub refused this process's session because the registration is gone,
     * and nothing has connected since.
     */
    var registrationDeleted: Boolean = false
        private set

    fun onLine(line: String) {
        when {
            isJobCompletion(line) -> jobCompleted = true
            isRegistrationDeleted(line) -> registrationDeleted = true
            // The runner can fall back to another stored registration and get
            // through, which makes the earlier refusal history.
            SessionConflict.isResolved(line) -> registrationDeleted = false
        }
    }

    companion object {
        /** The line a job ends on: `Job <name> completed with result: <result>`. */
        fun isJobCompletion(line: String): Boolean =
            line.contains("completed with result:", ignoreCase = true)

        /**
         * The runner's own words when GitHub no longer has its registration:
         * deleted after an ephemeral job, or after a long time offline.
         */
        fun isRegistrationDeleted(line: String): Boolean =
            line.contains("registration has been deleted from the server", ignoreCase = true)
    }
}
