package com.claimsai.platform.jobs.app;

/**
 * Does the work for one job type. Must be idempotent: a job can run more than once (a retry after a
 * timeout whose work had in fact committed, a lease taken over after a crash). Re-read the state and stop
 * if the work is already done.
 */
public interface JobHandler {

    /** The job type this handler runs, e.g. "VERIFY_POLICY". */
    String type();

    void handle(JobContext job);

    /**
     * true (default): the runner runs {@link #handle} and marks the job DONE in ONE transaction, so the
     * work and its completion commit together (exactly-once for database work).
     * <p>false: for handlers that call a remote system. A remote call must never hold a database
     * transaction open, so the handler opens its own short transactions around the call.
     */
    default boolean transactional() {
        return true;
    }
}
