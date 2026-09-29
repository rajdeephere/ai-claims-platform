package com.claimsai.platform.outbox.app;

import java.util.Set;

/**
 * Reacts to domain events after they are committed. Runs in its own transaction together with the
 * "processed" marker, so for database work each listener handles each event exactly once; for external
 * side effects (an e-mail) it is at-least-once.
 */
public interface OutboxListener {

    /** Stable name, stored in processed_event. Renaming it makes the listener see old events as new. */
    String name();

    Set<String> eventTypes();

    void on(OutboxMessage message);
}
