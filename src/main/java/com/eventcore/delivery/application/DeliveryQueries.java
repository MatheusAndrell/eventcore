package com.eventcore.delivery.application;

import com.eventcore.ingestion.application.EventQueries;
import com.eventcore.platform.identity.ClientId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DeliveryQueries {

    private final DeliveryStore store;
    private final EventQueries events;

    public DeliveryQueries(DeliveryStore store, EventQueries events) {
        this.store = store;
        this.events = events;
    }

    public Optional<List<DeliverySummary>> forEvent(ClientId clientId, UUID eventId) {
        return events.view(eventId)
                .filter(event -> event.clientId().equals(clientId))
                .map(event -> store.listForEvent(clientId, eventId));
    }

    public Optional<DeliveryDetails> find(ClientId clientId, UUID deliveryId) {
        return store.find(clientId, deliveryId)
                .map(delivery -> new DeliveryDetails(delivery, store.attempts(deliveryId)));
    }
}
