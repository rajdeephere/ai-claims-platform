package com.claimsai.platform.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes technical rows nobody needs any more, so the free-tier database (500 MB) doesn't fill up with
 * them. Business data (claims, audit trail, notifications) is never deleted here. Safe to run on several
 * instances at once: the deletes are idempotent.
 */
@Component
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration idempotencyRetention;
    private final Duration finishedWorkRetention;

    public Housekeeping(JdbcClient jdbc, Clock clock,
                        @Value("${app.housekeeping.idempotency-retention:24h}") Duration idempotencyRetention,
                        @Value("${app.housekeeping.finished-work-retention:7d}") Duration finishedWorkRetention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.idempotencyRetention = idempotencyRetention;
        this.finishedWorkRetention = finishedWorkRetention;
    }

    public record Result(int idempotencyRecords, int jobs, int outboxEvents) {
    }

    @Scheduled(cron = "${app.housekeeping.cron:0 17 * * * *}")
    @Transactional
    public Result run() {
        Instant now = clock.instant();
        // after a day, nobody is still retrying the same FNOL
        int keys = jdbc.sql("DELETE FROM idempotency_record WHERE created_at < :before")
                .param("before", Timestamp.from(now.minus(idempotencyRetention))).update();
        // FAILED jobs are kept: they are the ops queue
        int jobs = jdbc.sql("DELETE FROM job WHERE status IN ('DONE', 'CANCELLED') AND completed_at < :before")
                .param("before", Timestamp.from(now.minus(finishedWorkRetention))).update();
        // processed_event rows go with their event (ON DELETE CASCADE)
        int events = jdbc.sql("DELETE FROM outbox_event WHERE published_at < :before")
                .param("before", Timestamp.from(now.minus(finishedWorkRetention))).update();
        if (keys + jobs + events > 0) {
            log.info("Housekeeping removed {} idempotency records, {} finished jobs, {} published events",
                    keys, jobs, events);
        }
        return new Result(keys, jobs, events);
    }
}
