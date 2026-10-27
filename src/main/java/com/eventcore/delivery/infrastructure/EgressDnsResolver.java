package com.eventcore.delivery.infrastructure;

import com.eventcore.platform.egress.EgressPolicy;
import java.net.InetAddress;
import org.apache.hc.client5.http.DnsResolver;

final class EgressDnsResolver implements DnsResolver {

    private final EgressPolicy egressPolicy;

    EgressDnsResolver(EgressPolicy egressPolicy) {
        this.egressPolicy = egressPolicy;
    }

    @Override
    public InetAddress[] resolve(String host) {
        return egressPolicy.resolvePublic(host).toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
