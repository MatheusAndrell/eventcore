package com.eventcore.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.operations.application.Retention;
import com.eventcore.operations.application.RetentionResult;
import com.eventcore.subscription.application.CreatedSubscription;
import com.eventcore.support.DeliveryIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RetentionIT extends DeliveryIntegrationTest {

    @Autowired
    private Retention retention;

    @Test
    void removesOnlyExpiredDataAndKeepsWorkInProgress() {
        subscribe("/retained", "payment.captured");
        CreatedSubscription pendingTarget = subscribe("/pending-target", "order.placed");
        UUID expired = receivePayment("retention-expired");
        UUID recent = receivePayment("retention-recent");
        UUID stillDelivering = receivePayment("retention-in-progress");
        awaitDeliveries(expired, 1);
        awaitDeliveries(recent, 1);
        awaitDeliveries(stillDelivering, 1);
        jdbc.sql("""
                        insert into delivery (
                            id, event_id, subscription_id, client_id, status, attempts, max_attempts, next_attempt_at,
                            created_at, updated_at)
                        values (gen_random_uuid(), :eventId, :subscriptionId, :clientId, 'PENDING', 0, 3,
                                now() + interval '1 day', now(), now())
                        """)
                .param("eventId", stillDelivering)
                .param("subscriptionId", pendingTarget.subscription().id())
                .param("clientId", client.value())
                .update();
        backdate(expired);
        backdate(stillDelivering);
        jdbc.sql("update outbox_message set published_at = now() - interval '10 days' where status = 'PUBLISHED'")
                .update();
        long publishedMessages = count("select count(*) from outbox_message where status = 'PUBLISHED'");

        RetentionResult result = retention.purge();

        assertThat(result.events()).isEqualTo(1);
        assertThat(result.deliveries()).isEqualTo(2);
        assertThat(result.idempotencyRecords()).isEqualTo(2);
        assertThat(result.outboxMessages()).isEqualTo(publishedMessages);
        assertThat(exists("event", expired)).isFalse();
        assertThat(exists("event", recent)).isTrue();
        assertThat(exists("event", stillDelivering)).isTrue();
        assertThat(count("select count(*) from delivery where event_id = '" + expired + "'")).isZero();
        assertThat(count("select count(*) from delivery where event_id = '" + recent + "'")).isEqualTo(1);
        assertThat(count("select count(*) from delivery where event_id = '" + stillDelivering
                + "' and status = 'PENDING'")).isEqualTo(1);
        assertThat(count("select count(*) from delivery_attempt a join delivery d on d.id = a.delivery_id "
                + "where d.event_id = '" + recent + "'")).isEqualTo(1);
        assertThat(count("select count(*) from idempotency_record where event_id = '" + recent + "'"))
                .isEqualTo(1);
        assertThat(retention.purge().deletedAnything()).isFalse();
    }

    private void backdate(UUID eventId) {
        jdbc.sql("update event set received_at = now() - interval '40 days' where id = :id")
                .param("id", eventId).update();
        jdbc.sql("""
                        update delivery set completed_at = now() - interval '40 days'
                         where event_id = :id and status <> 'PENDING'
                        """).param("id", eventId).update();
        jdbc.sql("""
                        update idempotency_record
                           set created_at = now() - interval '41 days', expires_at = now() - interval '40 days'
                         where event_id = :id
                        """).param("id", eventId).update();
    }

    private boolean exists(String table, UUID id) {
        return count("select count(*) from " + table + " where id = '" + id + "'") == 1;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
