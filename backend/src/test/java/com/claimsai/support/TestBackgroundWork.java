package com.claimsai.support;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import com.claimsai.platform.jobs.app.PermanentJobFailure;
import com.claimsai.platform.outbox.app.OutboxListener;
import com.claimsai.platform.outbox.app.OutboxMessage;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only job types and outbox listeners, to exercise the queue and the relay without business logic.
 * TEST_FLAKY fails its first {@code failTimes} runs (from the payload).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBackgroundWork {

    public static final String OK = "TEST_OK";
    public static final String FLAKY = "TEST_FLAKY";
    public static final String PERMANENT = "TEST_PERMANENT";
    public static final String TEST_EVENT = "TEST_EVENT";

    /** job id -> how many times a handler ran it */
    public static final Map<Long, AtomicInteger> JOB_RUNS = new ConcurrentHashMap<>();
    /** job id -> correlation ID seen in the MDC while it ran */
    public static final Map<Long, String> JOB_CORRELATION = new ConcurrentHashMap<>();
    /** "listener:eventId" -> deliveries */
    public static final Map<String, AtomicInteger> DELIVERIES = new ConcurrentHashMap<>();

    static int count(JobContext job) {
        JOB_CORRELATION.put(job.id(), String.valueOf(MDC.get(CorrelationId.MDC_KEY)));
        return JOB_RUNS.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
    }

    static int failTimes(Map<String, Object> payload) {
        Object value = payload.get("failTimes");
        return value == null ? 0 : ((Number) value).intValue();
    }

    @Bean
    JobHandler testOkHandler() {
        return new Handler(OK) {
            @Override
            public void handle(JobContext job) {
                count(job);
            }
        };
    }

    @Bean
    JobHandler testFlakyHandler() {
        return new Handler(FLAKY) {
            @Override
            public void handle(JobContext job) {
                if (count(job) <= failTimes(job.payload())) {
                    throw new IllegalStateException("flaky failure on attempt " + job.attempt());
                }
            }
        };
    }

    @Bean
    JobHandler testPermanentHandler() {
        return new Handler(PERMANENT) {
            @Override
            public void handle(JobContext job) {
                count(job);
                throw new PermanentJobFailure("cannot ever work");
            }
        };
    }

    @Bean
    OutboxListener steadyListener() {
        return new Listener("test-steady", false);
    }

    @Bean
    OutboxListener flakyListener() {
        return new Listener("test-flaky", true);
    }

    abstract static class Handler implements JobHandler {
        private final String type;

        Handler(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }
    }

    static final class Listener implements OutboxListener {
        private final String name;
        private final boolean flaky;

        Listener(String name, boolean flaky) {
            this.name = name;
            this.flaky = flaky;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of(TEST_EVENT);
        }

        @Override
        public void on(OutboxMessage message) {
            int delivery = DELIVERIES.computeIfAbsent(name + ":" + message.id(), k -> new AtomicInteger())
                    .incrementAndGet();
            if (flaky && delivery <= failTimes(message.payload())) {
                throw new IllegalStateException("listener temporarily down");
            }
        }
    }
}
