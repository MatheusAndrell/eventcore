package com.eventcore.platform.egress;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

final class NonPublicAddresses {

    private static final List<AddressRange> IPV4 = ranges(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4");
    private static final AddressRange IPV6_GLOBAL_UNICAST = range("2000::/3");
    private static final AddressRange IPV6_NAT64 = range("64:ff9b::/96");
    private static final List<AddressRange> IPV6_SPECIAL_PURPOSE = ranges(
            "2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20", "5f00::/16");

    private NonPublicAddresses() {
    }

    static boolean includes(InetAddress address) {
        return switch (address) {
            case Inet4Address ipv4 -> includesIpv4(ipv4.getAddress());
            case Inet6Address ipv6 -> includesIpv6(ipv6.getAddress());
            default -> true;
        };
    }

    private static boolean includesIpv4(byte[] address) {
        return IPV4.stream().anyMatch(range -> range.contains(address));
    }

    private static boolean includesIpv6(byte[] address) {
        if (IPV6_NAT64.contains(address)) {
            return includesIpv4(Arrays.copyOfRange(address, 12, 16));
        }
        return !IPV6_GLOBAL_UNICAST.contains(address)
                || IPV6_SPECIAL_PURPOSE.stream().anyMatch(range -> range.contains(address));
    }

    private static List<AddressRange> ranges(String... cidrs) {
        return Stream.of(cidrs).map(NonPublicAddresses::range).toList();
    }

    private static AddressRange range(String cidr) {
        int separator = cidr.indexOf('/');
        return new AddressRange(InetAddress.ofLiteral(cidr.substring(0, separator)).getAddress(),
                Integer.parseInt(cidr.substring(separator + 1)));
    }

    private record AddressRange(byte[] network, int prefixLength) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / 8;
            for (int index = 0; index < wholeBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
