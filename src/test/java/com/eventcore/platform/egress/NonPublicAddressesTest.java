package com.eventcore.platform.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NonPublicAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "100.127.255.254", "127.0.0.1", "127.255.255.255",
            "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1",
            "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1",
            "239.255.255.250", "240.0.0.1", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::127.0.0.1", "fc00::1", "fd12:3456::1",
            "fe80::1", "fec0::1", "ff02::1", "100::1", "2001::1", "2001:db8::1", "2002:7f00:1::1",
            "3fff::1", "5f00::1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1"})
    void classifiesPrivateReservedAndSpecialPurposeAddressesAsNonPublic(String address) {
        assertThat(NonPublicAddresses.includes(InetAddress.ofLiteral(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1", "8.8.8.8", "93.184.215.14", "100.63.255.255", "100.128.0.0", "172.15.255.255",
            "172.32.0.0", "192.169.0.1", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "::ffff:8.8.8.8", "64:ff9b::808:808", "2606:4700:4700::1111", "2a00:1450:4001::200e",
            "2001:4860:4860::8888"})
    void classifiesGloballyRoutableUnicastAddressesAsPublic(String address) {
        assertThat(NonPublicAddresses.includes(InetAddress.ofLiteral(address))).isFalse();
    }
}
