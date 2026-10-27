package com.eventcore.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.EventcoreApplication;
import com.eventcore.ingestion.application.ReceiveEvent;
import com.eventcore.ingestion.application.ReceiveEventCommand;
import com.eventcore.ingestion.application.ReceiveEventResult;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.support.Containers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

@Testcontainers
class OutboxRelayIT {

    private static final String QUEUE = MessagingTopology.EVENT_ACCEPTED_QUEUE;
    private static final ClientId CLIENT = new ClientId(UUID.fromString("0192f3c8-0000-7000-8000-000000000001"));

    @Container
    static final PostgreSQLContainer postgres = Containers.postgres();

    @Container
    static final RabbitMQContainer rabbitmq = Containers.rabbitmq();

    @Test
    void publishesAcceptedEventsWithoutManualIntervention() {
        try (ConfigurableApplicationContext context = start("relay")) {
            JdbcClient jdbc = reset(context);
            jdbc.sql("insert into client (id, name, status, created_at) values (:id, 'acme', 'ACTIVE', now())")
                    .param("id", CLIENT.value()).update();

            ReceiveEventResult result = context.getBean(ReceiveEvent.class).receive(new ReceiveEventCommand(CLIENT,
                    new IdempotencyKey("key-1"), new CorrelationId("relay-it"), json("""
                            {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                             "source": "urn:acme:payments", "data": {"paymentId": "p-1", "orderId": "o-1",
                             "amount": "15.00", "currency": "BRL"}}
                            """)));
            String eventId = ((ReceiveEventResult.Accepted) result).receipt().id().toString();

            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                    select count(*) from outbox_message where status = 'PUBLISHED' and aggregate_id = :id
                    """).param("id", UUID.fromString(eventId)).query(Long.class).single() == 1);
            Message message = context.getBean(RabbitTemplate.class).receive(QUEUE, 5_000);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(eventId);
            assertThat(message.getMessageProperties().<String>getHeader("eventcore-aggregate-id")).isEqualTo(eventId);
        }
    }

    @Test
    void stoppingTheRelayReleasesEveryClaim() {
        int total = 3_000;
        try (ConfigurableApplicationContext context = start("relay")) {
            JdbcClient jdbc = reset(context);
            jdbc.sql("""
                    insert into outbox_message (
                        id, aggregate_type, aggregate_id, exchange, routing_key, message_type, message_version,
                        payload, status, available_at, created_at)
                    select gen_random_uuid(), 'event', gen_random_uuid(), :exchange, :routingKey, 'event.accepted', 1,
                           '{}'::jsonb, 'PENDING', now(), now()
                      from generate_series(1, :total)
                    """)
                    .param("exchange", MessagingTopology.EVENTS_EXCHANGE)
                    .param("routingKey", MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY)
                    .param("total", total)
                    .update();
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                    "select count(*) from outbox_message where status = 'PUBLISHED'").query(Long.class).single() > 0);
        }

        try (ConfigurableApplicationContext inspector = start("worker")) {
            JdbcClient after = inspector.getBean(JdbcClient.class);
            assertThat(after.sql("select count(*) from outbox_message where claim_token is not null")
                    .query(Long.class).single()).isZero();
            assertThat(after.sql("select count(*) from outbox_message").query(Long.class).single())
                    .isEqualTo(total);
        }
    }

    private static ConfigurableApplicationContext start(String roles) {
        return new SpringApplicationBuilder(EventcoreApplication.class).run(
                "--eventcore.roles=" + roles,
                "--management.server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.rabbitmq.host=" + rabbitmq.getHost(),
                "--spring.rabbitmq.port=" + rabbitmq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitmq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitmq.getAdminPassword(),
                "--eventcore.outbox.poll-interval=50ms",
                "--spring.rabbitmq.listener.simple.auto-startup=false");
    }

    private static JdbcClient reset(ConfigurableApplicationContext context) {
        JdbcClient jdbc = context.getBean(JdbcClient.class);
        jdbc.sql("truncate outbox_message, idempotency_record, event, api_key, client cascade").update();
        context.getBean(AmqpAdmin.class).purgeQueue(QUEUE, false);
        return jdbc;
    }

    private static JsonNode json(String text) {
        return new StrictJsonReader().read(text.getBytes(StandardCharsets.UTF_8));
    }
}
