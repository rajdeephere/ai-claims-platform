package com.claimsai.platform.outbox.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
public class OutboxStore {

    private final JdbcClient jdbc;

    public OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the oldest deliverable event for the current transaction. SKIP LOCKED: a second relay (another
     * instance) takes the next event instead of waiting, and the lock is held while listeners run, so two
     * relays never deliver the same event at once.
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: listeners run in their own transactions and
     * insert processed_event rows whose foreign key points at this row. An FK insert takes a KEY SHARE lock
     * on the parent, which FOR UPDATE blocks: the listener would wait for the relay and the relay for the
     * listener, forever. NO KEY UPDATE is exclusive between relays but lets FK checks through.
     */
    public Optional<Long> lockNextPending(Instant now) {
        return jdbc.sql("""
                        SELECT id FROM outbox_event
                        WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= :now
                        ORDER BY id
                        LIMIT 1
                        FOR NO KEY UPDATE SKIP LOCKED""")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    public boolean isProcessed(Long eventId, String listener) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :id AND listener = :listener")
                .param("id", eventId)
                .param("listener", listener)
                .query(Long.class)
                .single() > 0;
    }

    public void markProcessed(Long eventId, String listener, Instant now) {
        jdbc.sql("INSERT INTO processed_event (event_id, listener, processed_at) VALUES (:id, :listener, :now)")
                .param("id", eventId)
                .param("listener", listener)
                .param("now", Timestamp.from(now))
                .update();
    }
}
