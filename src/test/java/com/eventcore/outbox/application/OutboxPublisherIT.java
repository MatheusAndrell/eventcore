package com.eventcore.outbox.application;

import static com.eventcore.support.OutboxTestSupport.drain;
import static com.eventcore.support.OutboxTestSupport.insertPending;
import static com.eventcore.support.OutboxTestSupport.insertPendingEventAccepted;
import static com.eventcore.support.OutboxTestSupport.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "eventcore.roles=worker")
@Import(TestcontainersConfiguration.class)
class OutboxPublisherIT {

    private static final String QUEUE = MessagingTopology.EVENT_ACCEPTED_QUEUE;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxStore store;

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate outbox_message").update();
        amqpAdmin.purgeQueue(QUEUE, false);
    }

    @Test
    void publishesPendingMessagesWithTheirMetadataAndMarksThemPublished() {
        List<UUID> ids = List.of(insertPendingEventAccepted(jdbc), insertPendingEventAccepted(jdbc),
                insertPendingEventAccepted(jdbc));

        BatchResult result = publisher.publishBatch();

        assertThat(result).isEqualTo(new BatchResult(3, 3, 0, false));
        assertThat(ids).allSatisfy(id -> assertThat(status(jdbc, id)).isEqualTo("PUBLISHED"));
        assertThat(jdbc.sql("""
                select count(*) from outbox_message
                 where published_at is not null and claim_token is null and claimed_until is null
                """).query(Long.class).single()).isEqualTo(3);
        List<Message> messages = drain(rabbit, QUEUE);
        assertThat(messages).extracting(message -> UUID.fromString(message.getMessageProperties().getMessageId()))
                .containsExactlyInAnyOrderElementsOf(ids);
        Message first = messages.getFirst();
        assertThat(first.getMessageProperties().getType()).isEqualTo("event.accepted");
        assertThat(first.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(first.getMessageProperties().<Integer>getHeader("eventcore-message-version")).isEqualTo(1);
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).contains("\"eventId\"");
    }

    @Test
    void concurrentPublishersPublishEveryMessageExactlyOnce() throws Exception {
        int total = 500;
        for (int index = 0; index < total; index++) {
            insertPendingEventAccepted(jdbc);
        }
        OutboxPublisher smallBatches = new OutboxPublisher(store, messagePublisher,
                properties(25, Duration.ofSeconds(30), Duration.ofSeconds(5)));
        List<Future<Integer>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            Callable<Integer> drainOutbox = () -> {
                int published = 0;
                BatchResult batch;
                while ((batch = smallBatches.publishBatch()).claimed() > 0) {
                    published += batch.published();
                }
                return published;
            };
            for (int thread = 0; thread < 4; thread++) {
                results.add(executor.submit(drainOutbox));
            }
            int published = 0;
            for (Future<Integer> result : results) {
                published += result.get();
            }
            assertThat(published).isEqualTo(total);
        }

        List<Message> messages = drain(rabbit, QUEUE);
        Set<String> distinctIds = new HashSet<>();
        messages.forEach(message -> distinctIds.add(message.getMessageProperties().getMessageId()));
        assertThat(messages).hasSize(total);
        assertThat(distinctIds).hasSize(total);
        assertThat(jdbc.sql("select count(*) from outbox_message where status = 'PUBLISHED'").query(Long.class)
                .single()).isEqualTo(total);
    }

    @Test
    void neverMarksAnUnroutableMessageAsPublished() {
        UUID id = insertPending(jdbc, MessagingTopology.EVENTS_EXCHANGE, "nowhere");

        BatchResult result = publisher.publishBatch();

        assertThat(result).isEqualTo(new BatchResult(1, 0, 1, false));
        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("publish_attempts", 1)
                .containsEntry("claimed", false).containsEntry("delayed", true);
        assertThat((String) row.get("last_error")).contains("returned 312 NO_ROUTE");
        assertThat(drain(rabbit, QUEUE)).isEmpty();
    }

    @Test
    void reschedulesMessagesForAMissingExchange() {
        UUID id = insertPending(jdbc, "eventcore.missing", "event.accepted");

        BatchResult result = publisher.publishBatch();

        assertThat(result.published()).isZero();
        assertThat(result.rescheduled()).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("publish_attempts", 1);
        assertThat(row.get("last_error")).isNotNull();
    }

    @Test
    void respectsActiveLeasesAndRecoversExpiredOnes() {
        UUID id = insertPendingEventAccepted(jdbc);
        assertThat(store.claim(UUID.randomUUID(), Duration.ofMinutes(5), 10)).hasSize(1);

        assertThat(publisher.publishBatch().claimed()).isZero();

        jdbc.sql("update outbox_message set claimed_until = now() - interval '1 second' where id = :id")
                .param("id", id).update();
        assertThat(publisher.publishBatch()).isEqualTo(new BatchResult(1, 1, 0, false));
        assertThat(status(jdbc, id)).isEqualTo("PUBLISHED");
    }

    @Test
    void republishesAMessageConfirmedByTheBrokerButNotRecordedBeforeACrash() {
        UUID id = insertPendingEventAccepted(jdbc);
        AtomicBoolean failNextMark = new AtomicBoolean(true);
        OutboxStore crashingStore = new OutboxStore() {
            @Override
            public List<PendingMessage> claim(UUID claimToken, Duration lease, int batchSize) {
                return store.claim(claimToken, lease, batchSize);
            }

            @Override
            public boolean markPublished(UUID messageId, UUID claimToken) {
                if (failNextMark.getAndSet(false)) {
                    throw new DataAccessResourceFailureException("simulated database outage");
                }
                return store.markPublished(messageId, claimToken);
            }

            @Override
            public boolean reschedule(UUID messageId, UUID claimToken, Duration delay, String error) {
                return store.reschedule(messageId, claimToken, delay, error);
            }
        };
        OutboxPublisher shortLease = new OutboxPublisher(crashingStore, messagePublisher,
                properties(10, Duration.ofSeconds(1), Duration.ofMillis(400)));

        assertThatThrownBy(shortLease::publishBatch).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(status(jdbc, id)).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> shortLease.publishBatch().published() == 1);

        List<Message> messages = drain(rabbit, QUEUE);
        assertThat(messages).hasSize(2).allSatisfy(message ->
                assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString()));
        assertThat(status(jdbc, id)).isEqualTo("PUBLISHED");
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.sql("""
                        select status, publish_attempts, last_error, claim_token is not null as claimed,
                               available_at > now() as delayed
                          from outbox_message where id = :id
                        """)
                .param("id", id).query().singleRow();
    }

    private static OutboxProperties properties(int batchSize, Duration lease, Duration confirmTimeout) {
        return new OutboxProperties(batchSize, Duration.ofMillis(50), lease, confirmTimeout, Duration.ofSeconds(1),
                Duration.ofMinutes(5));
    }
}
