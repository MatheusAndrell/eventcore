package com.eventcore.support;

import com.eventcore.platform.egress.EgressPolicy;
import com.eventcore.platform.egress.EgressProperties;
import com.eventcore.platform.egress.HostResolver;
import java.net.InetAddress;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;

@TestConfiguration(proxyBeanMethods = false)
public class ReceiverEgressConfiguration {

    public static void registerReceiver(DynamicPropertyRegistry registry, WebhookReceiver receiver) {
        registry.add("eventcore.egress.allowed-ports",
                () -> "443," + receiver.port() + "," + receiver.tricklingPort());
        registry.add("eventcore.delivery.ssl-bundle", () -> "webhook-receivers");
        registry.add("spring.ssl.bundle.jks.webhook-receivers.truststore.location",
                () -> receiver.trustStore().toUri().toString());
        registry.add("spring.ssl.bundle.jks.webhook-receivers.truststore.password", receiver::trustStorePassword);
        registry.add("spring.ssl.bundle.jks.webhook-receivers.truststore.type", () -> "PKCS12");
    }

    @Bean
    @Primary
    EgressPolicy receiverAwareEgressPolicy(HostResolver hostResolver, EgressProperties properties) {
        return new EgressPolicy(hostResolver, properties) {
            @Override
            public List<InetAddress> resolvePublic(String host) {
                return WebhookReceiver.HOST.equals(host)
                        ? List.of(InetAddress.getLoopbackAddress())
                        : super.resolvePublic(host);
            }
        };
    }
}
