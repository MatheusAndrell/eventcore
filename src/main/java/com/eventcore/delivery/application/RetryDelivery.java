package com.eventcore.delivery.application;

import com.eventcore.outbox.application.Outbox;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@ConditionalOnRole(Role.API)
public class RetryDelivery {

    private final DeliveryStore store;
    private final DeliveryQueries queries;
    private final Outbox outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final DeliveryProperties properties;

    public RetryDelivery(
            DeliveryStore store,
            DeliveryQueries queries,
            Outbox outbox,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            DeliveryProperties properties) {
        this.store = store;
        this.queries = queries;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    public RetryOutcome retry(ClientId clientId, UUID deliveryId) {
        Boolean reopened = transactions.execute(status -> {
            if (!store.reopen(clientId, deliveryId, properties.maxAttempts())) {
                return false;
            }
            outbox.enqueue(DeliveryRequestedMessage.of(ids.next(), deliveryId,
                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
            return true;
        });
        return queries.find(clientId, deliveryId)
                .<RetryOutcome>map(details -> Boolean.TRUE.equals(reopened)
                        ? new RetryOutcome.Scheduled(details)
                        : new RetryOutcome.NotRetriable(details.delivery().status()))
                .orElseGet(RetryOutcome.NotFound::new);
    }
}
