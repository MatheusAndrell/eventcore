package com.eventcore.outbox.application;

import static com.eventcore.support.OutboxTestSupport.drain;
import static com.eventcore.support.OutboxTestSupport.insertPendingEventAccepted;
import static com.eventcore.support.OutboxTestSupport.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.support.Containers;
import com.eventcore.support.PostgresContainerConfiguration;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "eventcore.roles=worker")
@Import(PostgresContainerConfiguration.class)
class OutboxBrokerFailureIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final int PROXIED_PORT = 8666;
    private static final String QUEUE = MessagingTopology.EVENT_ACCEPTED_QUEUE;

    @Container
    static final RabbitMQContainer rabbitmq = Containers.rabbitmq().withNetwork(NETWORK).withNetworkAliases("rabbitmq");

    @Container
    static final ToxiproxyContainer toxiproxy = Containers.toxiproxy().withNetwork(NETWORK);

    private static Proxy proxy;

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

    @BeforeAll
    static void createProxy() throws IOException {
        proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                .createProxy("rabbitmq", "0.0.0.0:" + PROXIED_PORT, "rabbitmq:5672");
    }

    @DynamicPropertySource
    static void brokerThroughProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", toxiproxy::getHost);
        registry.add("spring.rabbitmq.port", () -> toxiproxy.getMappedPort(PROXIED_PORT));
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("truncate outbox_message").update();
        amqpAdmin.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void restoreNetwork() throws IOException {
        proxy.enable();
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    @Test
    void keepsMessagesPendingWhileTheBrokerIsUnreachableAndPublishesThemAfterwards() throws IOException {
        OutboxPublisher outbox = publisher(Duration.ofSeconds(2));
        UUID id = insertPendingEventAccepted(jdbc);
        proxy.disable();

        BatchResult whileDown = outbox.publishBatch();

        assertThat(whileDown.published()).isZero();
        assertThat(whileDown.rescheduled()).isEqualTo(1);
        assertThat(status(jdbc, id)).isEqualTo("PENDING");
        assertThat(attempts(id)).isEqualTo(1);

        proxy.enable();
        makeAvailableNow();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> outbox.publishBatch().published() == 1 || "PUBLISHED".equals(status(jdbc, id)));

        assertThat(status(jdbc, id)).isEqualTo("PUBLISHED");
        assertThat(drain(rabbit, QUEUE)).extracting(message -> message.getMessageProperties().getMessageId())
                .contains(id.toString());
    }

    @Test
    void doesNotMarkAMessagePublishedWhenTheConfirmationArrivesTooLate() throws IOException {
        OutboxPublisher outbox = publisher(Duration.ofMillis(500));
        UUID id = insertPendingEventAccepted(jdbc);
        proxy.toxics().latency("slow-confirms", ToxicDirection.DOWNSTREAM, 3_000);

        BatchResult slow = outbox.publishBatch();

        assertThat(slow.published()).isZero();
        assertThat(status(jdbc, id)).isEqualTo("PENDING");
        assertThat(jdbc.sql("select last_error from outbox_message where id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("confirmation timed out");

        proxy.toxics().get("slow-confirms").remove();
        makeAvailableNow();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> outbox.publishBatch().published() == 1 || "PUBLISHED".equals(status(jdbc, id)));

        assertThat(drain(rabbit, QUEUE)).isNotEmpty().allSatisfy(message ->
                assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString()));
    }

    private OutboxPublisher publisher(Duration confirmTimeout) {
        return new OutboxPublisher(store, messagePublisher, new OutboxProperties(10, Duration.ofMillis(50),
                Duration.ofSeconds(10), confirmTimeout, Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    private void makeAvailableNow() {
        jdbc.sql("update outbox_message set available_at = now() where status = 'PENDING'").update();
    }

    private int attempts(UUID id) {
        return jdbc.sql("select publish_attempts from outbox_message where id = :id").param("id", id)
                .query(Integer.class).single();
    }
}
