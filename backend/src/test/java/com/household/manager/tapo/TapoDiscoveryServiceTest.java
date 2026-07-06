package com.household.manager.tapo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for UDP discovery targeting: a single limited broadcast to
 * 255.255.255.255 leaves multi-homed hosts (VPN, WSL, LAN adapters) on the wrong
 * interface, so subnet-directed broadcasts per interface must be included.
 */
class TapoDiscoveryServiceTest {

    @Test
    @DisplayName("Konfiguriertes Discovery-Ziel bleibt enthalten")
    void broadcastTargetsIncludeConfiguredTarget() throws Exception {
        TapoProperties properties = new TapoProperties();
        properties.setLocalDiscoveryTarget("255.255.255.255");

        Set<InetAddress> targets = TapoDiscoveryService.resolveBroadcastTargets(properties);

        assertTrue(targets.contains(InetAddress.getByName("255.255.255.255")),
                "Das konfigurierte Discovery-Ziel muss enthalten sein");
    }

    @Test
    @DisplayName("Subnetz-Broadcast-Adressen aller aktiven IPv4-Interfaces sind enthalten")
    void broadcastTargetsIncludeSubnetBroadcastOfEveryActiveIpv4Interface() throws Exception {
        Set<InetAddress> expected = new HashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) {
                    expected.add(address.getBroadcast());
                }
            }
        }

        TapoProperties properties = new TapoProperties();
        Set<InetAddress> targets = TapoDiscoveryService.resolveBroadcastTargets(properties);

        assertTrue(targets.containsAll(expected),
                "Alle Interface-Broadcast-Adressen muessen enthalten sein; fehlend: "
                        + expected.stream().filter(a -> !targets.contains(a)).toList());
    }
}
