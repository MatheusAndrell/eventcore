package com.eventcore.platform.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EgressPolicyTest {

    private static final Map<String, List<InetAddress>> HOSTS = Map.of(
            "hooks.acme.example", addresses("93.184.215.14", "2606:4700::6810:84e5"),
            "internal.acme.example", addresses("10.0.0.5"),
            "metadata.acme.example", addresses("169.254.169.254"),
            "rebind.acme.example", addresses("93.184.215.14", "127.0.0.1"),
            "empty.acme.example", List.of());

    private final List<String> lookups = new ArrayList<>();
    private final EgressPolicy policy = new EgressPolicy(this::resolve, new EgressProperties(Set.of(443, 8443)));

    @Test
    void allowsHttpsDestinationsThatResolveOnlyToPublicAddresses() {
        policy.check(URI.create("https://hooks.acme.example/webhooks?tenant=1"));
        policy.check(URI.create("https://hooks.acme.example:8443/webhooks"));
        policy.check(URI.create("https://hooks.acme.example./webhooks"));
        policy.check(URI.create("https://93.184.215.14/webhooks"));
        policy.check(URI.create("https://[2606:4700::6810:84e5]/webhooks"));

        assertThat(policy.resolvePublic("hooks.acme.example")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://internal.acme.example/hooks",
            "https://metadata.acme.example/latest/meta-data",
            "https://rebind.acme.example/hooks",
            "https://127.0.0.1/hooks",
            "https://10.0.0.1/hooks",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/hooks",
            "https://[::ffff:127.0.0.1]/hooks",
            "https://[fd00::1]/hooks",
            "https://[64:ff9b::a9fe:a9fe]/hooks",
            "https://0.0.0.0/hooks"})
    void deniesDestinationsThatReachNonPublicAddresses(String uri) {
        assertThatThrownBy(() -> policy.check(URI.create(uri)))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessage("host resolves to a non-public address");
    }

    @Test
    void deniesDestinationsOutsideTheAllowedShape() {
        assertDenied("http://hooks.acme.example/hooks", "only https destinations are allowed");
        assertDenied("https://user:pass@hooks.acme.example/hooks", "destinations must not contain user information");
        assertDenied("https://hooks.acme.example:22/hooks", "port 22 is not an allowed destination port");
        assertDenied("https://hooks.acme.example:80/hooks", "port 80 is not an allowed destination port");
        assertDenied("https://bad_host.acme.example/hooks", "destinations must have a host name");
    }

    @Test
    void deniesNamesThatAreNotQualifiedOrDoNotResolve() {
        assertDenied("https://localhost/hooks", "host must be a fully qualified domain name");
        assertDenied("https://intranet./hooks", "host must be a fully qualified domain name");
        assertDenied("https://unknown.acme.example/hooks", "host could not be resolved");
        assertDenied("https://empty.acme.example/hooks", "host could not be resolved");
        assertThat(lookups).containsExactly("unknown.acme.example", "empty.acme.example");
    }

    @Test
    void neverResolvesNumericHostsThroughDns() {
        assertDenied("https://2130706433/hooks", "host resolves to a non-public address");
        assertDenied("https://999.1.1.1/hooks", "destinations must have a host name");
        assertThat(lookups).isEmpty();
        assertThatThrownBy(() -> policy.resolvePublic("999.1.1.1"))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessage("host is not a valid IP address");
        assertThatThrownBy(() -> policy.resolvePublic("[fe80::1%eth0"))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessage("host is not a valid IP address");
    }

    private void assertDenied(String uri, String reason) {
        assertThatThrownBy(() -> policy.check(URI.create(uri)))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessage(reason);
    }

    private List<InetAddress> resolve(String host) throws UnknownHostException {
        lookups.add(host);
        List<InetAddress> addresses = HOSTS.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses;
    }

    private static List<InetAddress> addresses(String... literals) {
        return List.of(literals).stream().map(InetAddress::ofLiteral).toList();
    }
}
