package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.outbox.application.Outbox;
import com.eventcore.platform.identity.UuidV7Generator;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class EventDispatchTracking {

    private final EventStore store;
    private final Outbox outbox;
    private final UuidV7Generator ids;
    private final TransactionOperations transactions;

    public EventDispatchTracking(
            EventStore store, Outbox outbox, UuidV7Generator ids, TransactionOperations transactions) {
        this.store = store;
        this.outbox = outbox;
        this.ids = ids;
        this.transactions = transactions;
    }

    public boolean markDispatched(UUID eventId) {
        return store.markDispatched(new EventId(eventId));
    }

    public int requeueUndispatched(Duration staleAfter, int limit) {
        Integer requeued = transactions.execute(status -> {
            List<Event> events = store.claimUndispatched(staleAfter, limit);
            events.forEach(event -> outbox.enqueue(EventAcceptedMessage.of(ids.next(), event)));
            return events.size();
        });
        return requeued == null ? 0 : requeued;
    }
}
