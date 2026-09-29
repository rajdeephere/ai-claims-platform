package com.claimsai.platform.outbox.app;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.platform.outbox.domain.OutboxEvent;
import com.claimsai.platform.outbox.infra.OutboxEventRepository;
import com.claimsai.platform.outbox.infra.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Delivers committed outbox events to in-process listeners (ADR-0017). Per event, in an outer transaction
 * that holds the event's row lock:
 * <ol>
 *   <li>each interested listener that hasn't handled it yet runs in its own transaction
 *       (REQUIRES_NEW) together with its processed_event row</li>
 *   <li>all succeeded: the event is marked published; any failed: it is retried later, and listeners that
 *       already succeeded are skipped next time</li>
 * </ol>
 * Order: events are delivered oldest first, but a retried event can be overtaken by later ones. Listeners
 * must not depend on strict order (the notification listener doesn't).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final OutboxEventRepository events;
    private final List<OutboxListener> listeners;
    private final OutboxProperties properties;
    private final TransactionTemplate outer;
    private final TransactionTemplate perListener;
    private final Clock clock;

    public OutboxRelay(OutboxStore store, OutboxEventRepository events, List<OutboxListener> listeners,
                       OutboxProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.events = events;
        this.listeners = listeners;
        this.properties = properties;
        this.outer = new TransactionTemplate(transactionManager);
        this.perListener = new TransactionTemplate(transactionManager);
        this.perListener.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void poll() {
        if (properties.enabled()) {
            relayPending();
        }
    }

    /** Delivers up to one batch; returns how many events it looked at. */
    public int relayPending() {
        int handled = 0;
        while (handled < properties.batchSize()) {
            Boolean found = outer.execute(status -> deliverNext());
            if (!Boolean.TRUE.equals(found)) {
                break;
            }
            handled++;
        }
        return handled;
    }

    private boolean deliverNext() {
        Instant now = clock.instant();
        Optional<Long> next = store.lockNextPending(now);
        if (next.isEmpty()) {
            return false;
        }
        OutboxEvent event = events.findById(next.get()).orElseThrow();
        OutboxMessage message = OutboxMessage.of(event);
        String previousCorrelation = MDC.get(CorrelationId.MDC_KEY);
        if (event.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.getCorrelationId());
        }
        try {
            String failure = null;
            for (OutboxListener listener : listeners) {
                if (!listener.eventTypes().contains(event.getEventType())) {
                    continue;
                }
                try {
                    perListener.executeWithoutResult(status -> {
                        if (!store.isProcessed(event.getId(), listener.name())) {
                            listener.on(message);
                            store.markProcessed(event.getId(), listener.name(), clock.instant());
                        }
                    });
                } catch (RuntimeException e) {
                    failure = listener.name() + ": " + e.getMessage();
                    log.warn("Outbox event {} {} failed in listener {}: {}", event.getId(), event.getEventType(),
                            listener.name(), e.getMessage());
                }
            }
            if (failure == null) {
                event.markPublished(clock.instant());
            } else {
                event.recordFailure(failure, properties.maxAttempts(), now.plus(properties.retryDelay()), now);
                if (event.getFailedAt() != null) {
                    log.error("Outbox event {} {} parked after {} attempts: {}", event.getId(), event.getEventType(),
                            event.getAttempts(), failure);
                }
            }
            return true;
        } finally {
            if (previousCorrelation == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previousCorrelation);
            }
        }
    }
}
