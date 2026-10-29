package com.eventcore.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.access.application.ClientAdministration;
import com.eventcore.access.domain.ClientName;
import com.eventcore.ingestion.application.ReceiveEvent;
import com.eventcore.ingestion.application.ReceiveEventCommand;
import com.eventcore.ingestion.application.ReceiveEventResult;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.messaging.MessageHeaders;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.subscription.application.SubscriptionManagement;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.WebhookEndpoint;
import com.eventcore.support.Containers;
import com.eventcore.support.ReceiverEgressConfiguration;
import com.eventcore.support.StaticHostResolverConfiguration;
import com.eventcore.support.WebhookReceiver;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eventcore.roles=api,relay,worker",
                "eventcore.outbox.poll-interval=50ms",
                "eventcore.delivery.connect-timeout=1s",
                "eventcore.delivery.response-timeout=3s",
                "eventcore.delivery.request-timeout=4s",
                "eventcore.delivery.lease=8s",
                "eventcore.delivery.initial-retry-delay=100ms",
                "eventcore.delivery.max-retry-delay=200ms",
                "eventcore.reconciler.interval=300ms",
                "eventcore.reconciler.stale-after=2s",
                "spring.rabbitmq.listener.simple.retry.initial-interval=200ms",
                "spring.rabbitmq.listener.simple.retry.max-interval=400ms",
                "spring.rabbitmq.listener.simple.retry.max-retries=2",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.validation-timeout=500"})
@Import({StaticHostResolverConfiguration.class, ReceiverEgressConfiguration.class})
class DatabaseOutageIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final int PROXIED_PORT = 8666;
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(60);
    private static final WebhookReceiver receiver = WebhookReceiver.shared();

    @Container
    static final PostgreSQLContainer postgres = Containers.postgres().withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    @Container
    static final ToxiproxyContainer toxiproxy = Containers.toxiproxy().withNetwork(NETWORK);

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitmq = Containers.rabbitmq();

    private static Proxy database;

    @Autowired
    private ClientAdministration administration;

    @Autowired
    private SubscriptionManagement subscriptions;

    @Autowired
    private ReceiveEvent receiveEvent;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private ClientId client;

    @BeforeAll
    static void createProxy() throws IOException {
        database = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                .createProxy("postgres", "0.0.0.0:" + PROXIED_PORT, "postgres:5432");
    }

    @DynamicPropertySource
    static void databaseThroughProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + toxiproxy.getHost() + ":"
                + toxiproxy.getMappedPort(PROXIED_PORT) + "/" + postgres.getDatabaseName());
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        ReceiverEgressConfiguration.registerReceiver(registry, receiver);
    }

    @BeforeEach
    void reset() throws IOException, SQLException {
        database.enable();
        directly("truncate outbox_message, idempotency_record, event, api_key, client cascade");
        receiver.reset();
        client = administration.createClient(new ClientName("acme")).id();
    }

    @AfterEach
    void restoreDatabase() throws IOException {
        database.enable();
    }

    @Test
    void deliversEventsWhoseDispatchFailedWhileTheDatabaseWasDown() throws Exception {
        subscriptions.create(client, WebhookEndpoint.parse(receiver.url("/outage")),
                SubscribedEventTypes.of(List.of("payment.captured")));
        UUID eventId = UUID.randomUUID();
        directly("""
                insert into event (
                    id, client_id, type, data_version, source, occurred_at, received_at, data, status,
                    correlation_id)
                values ('%s', '%s', 'payment.captured', 1, 'urn:acme:payments', now(), now(),
                        '{"paymentId":"p-1","orderId":"o-1","amount":"1.00","currency":"BRL"}', 'RECEIVED',
                        'outage-it')
                """.formatted(eventId, client.value()));
        long parkedBefore = parked();

        database.disable();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY, MessageBuilder
                .withBody(("{\"eventId\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setType("event.accepted")
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader(MessageHeaders.MESSAGE_VERSION, 1)
                .build());
        await().atMost(RECOVERY_TIMEOUT).until(() -> parked() > parkedBefore);
        assertThat(receiver.received("/outage")).isEmpty();
        database.enable();

        await().atMost(RECOVERY_TIMEOUT).until(() -> receiver.received("/outage").size() == 1);
        await().atMost(RECOVERY_TIMEOUT).until(() -> "DISPATCHED".equals(
                queryString("select status from event where id = '" + eventId + "'")));
        assertThat(receiver.received("/outage").getFirst().header("webhook-id")).isEqualTo(eventId.toString());
        assertThat(queryString("select status from delivery where event_id = '" + eventId + "'"))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void redeliversWithTheSameIdWhenTheOutcomeCouldNotBeRecorded() throws Exception {
        subscriptions.create(client, WebhookEndpoint.parse(receiver.url("/unrecorded")),
                SubscribedEventTypes.of(List.of("payment.captured")));
        receiver.replySlowly("/unrecorded", Duration.ofMillis(1500));

        long parkedBefore = parked();
        UUID eventId = receivePayment();
        await().atMost(RECOVERY_TIMEOUT).until(() -> receiver.received("/unrecorded").size() == 1);
        database.disable();
        await().atMost(RECOVERY_TIMEOUT).until(() -> parked() > parkedBefore);
        receiver.reply("/unrecorded", 204);
        database.enable();

        await().atMost(RECOVERY_TIMEOUT).until(() -> "SUCCEEDED".equals(
                queryString("select status from delivery where event_id = '" + eventId + "'")));
        assertThat(receiver.received("/unrecorded")).hasSize(2)
                .extracting(webhook -> webhook.header("webhook-id"))
                .containsOnly(eventId.toString());
        assertThat(queryString("select count(*)::text from delivery_attempt a join delivery d on d.id = a.delivery_id "
                + "where d.event_id = '" + eventId + "' and a.succeeded")).isEqualTo("1");
    }

    private UUID receivePayment() {
        ReceiveEventResult result = receiveEvent.receive(new ReceiveEventCommand(client,
                new IdempotencyKey("outage-" + UUID.randomUUID()), new CorrelationId("outage-it"),
                new StrictJsonReader().read("""
                        {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                         "source": "urn:acme:payments", "data": {"paymentId": "p-2", "orderId": "o-2",
                         "amount": "2.00", "currency": "BRL"}}
                        """.getBytes(StandardCharsets.UTF_8))));
        return UUID.fromString(((ReceiveEventResult.Accepted) result).receipt().id().toString());
    }

    private long parked() {
        QueueInformation queue = amqpAdmin.getQueueInfo(MessagingTopology.PARKING_QUEUE);
        return queue == null ? 0 : queue.getMessageCount();
    }

    private static void directly(String sql) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
