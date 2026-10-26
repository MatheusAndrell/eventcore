package com.eventcore.platform.egress;

import java.net.InetAddress;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EgressConfiguration {

    @Bean
    HostResolver systemHostResolver() {
        return host -> List.of(InetAddress.getAllByName(host));
    }

    @Bean
    EgressPolicy egressPolicy(HostResolver hostResolver, EgressProperties properties) {
        return new EgressPolicy(hostResolver, properties);
    }
}
