package com.claimsai.platform.outbox.app;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.platform.outbox.domain.OutboxEvent;
import com.claimsai.platform.outbox.infra.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

@Service
public class OutboxService {

    private final OutboxEventRepository events;
    private final Clock clock;

    public OutboxService(OutboxEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** MANDATORY: an event is only ever written together with the change it announces. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, Long aggregateId, String eventType, Map<String, Object> payload) {
        events.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload, CorrelationId.current(),
                clock.instant()));
    }
}
