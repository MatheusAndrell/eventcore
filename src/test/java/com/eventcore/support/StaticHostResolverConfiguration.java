package com.eventcore.support;

import com.eventcore.platform.egress.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class StaticHostResolverConfiguration {

    public static final String PUBLIC_HOST = "hooks.acme.example";
    public static final String OTHER_PUBLIC_HOST = "hooks.globex.example";
    public static final String PRIVATE_HOST = "internal.acme.example";
    public static final String REBINDING_HOST = "rebind.acme.example";

    private static final Map<String, List<InetAddress>> HOSTS = Map.of(
            PUBLIC_HOST, List.of(InetAddress.ofLiteral("93.184.215.14")),
            OTHER_PUBLIC_HOST, List.of(InetAddress.ofLiteral("2606:4700::6810:84e5")),
            PRIVATE_HOST, List.of(InetAddress.ofLiteral("10.0.0.5")),
            REBINDING_HOST, List.of(InetAddress.ofLiteral("93.184.215.14"), InetAddress.ofLiteral("127.0.0.1")));

    @Bean
    @Primary
    HostResolver staticHostResolver() {
        return host -> {
            List<InetAddress> addresses = HOSTS.get(host);
            if (addresses == null) {
                throw new UnknownHostException(host);
            }
            return addresses;
        };
    }
}
