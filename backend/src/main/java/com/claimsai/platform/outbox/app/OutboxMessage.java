package com.claimsai.platform.outbox.app;

import com.claimsai.platform.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/** An event as listeners see it: immutable, self-contained (the payload carries what they need). */
public record OutboxMessage(Long id, String aggregateType, Long aggregateId, String eventType,
                            Map<String, Object> payload, String correlationId, Instant createdAt) {

    static OutboxMessage of(OutboxEvent e) {
        return new OutboxMessage(e.getId(), e.getAggregateType(), e.getAggregateId(), e.getEventType(),
                Map.copyOf(e.getPayload()), e.getCorrelationId(), e.getCreatedAt());
    }

    public String text(String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    public Long number(String key) {
        Object value = payload.get(key);
        return value == null ? null : ((Number) value).longValue();
    }
}
