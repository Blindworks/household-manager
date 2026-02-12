package com.household.manager.tapo.service;

import com.household.manager.tapo.config.TapoProperties;
import com.household.manager.tapo.exception.TapoException;
import com.household.manager.tapo.model.TapoDevice;
import com.household.manager.tapo.protocol.KlapProtocolClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDiscoveryService {

    private static final int SCAN_CONNECT_TIMEOUT_MS = 300;
    private static final int SCAN_THREAD_POOL_SIZE = 100;
    private static final int SCAN_TIMEOUT_MINUTES = 2;
    private static final List<String> EXCLUDED_INTERFACE_KEYWORDS = List.of(
            "virtual",
            "vethernet",
            "wsl",
            "hyper-v",
            "docker"
    );

    private final TapoProperties tapoProperties;

    @Qualifier("klapProtocolClientV2")
    private final KlapProtocolClientV2 klapProtocolClient;

    public List<TapoDevice> discoverDevices() {
        Map<String, TapoDevice> discovered = new LinkedHashMap<>();
        addConfiguredDevices(discovered);

        log.info("Starting Tapo device discovery via subnet scan (Tapo devices don't support mDNS)...");
        List<DiscoveryInterface> interfaces = resolveDiscoveryInterfaces();

        if (interfaces.isEmpty()) {
            log.warn("No network interfaces found for Tapo discovery");
            return new ArrayList<>(discovered.values());
        }

        int scanFound = discoverViaSubnetScan(discovered, interfaces);
        log.info("Tapo subnet scan finished, found {} new device(s)", scanFound);

        List<TapoDevice> result = new ArrayList<>(discovered.values());
        log.info("Tapo discovery completed, total devices: {}", result.size());
        return result;
    }

    private void addConfiguredDevices(Map<String, TapoDevice> discovered) {
        for (String deviceIp : tapoProperties.getDevices()) {
            if (deviceIp == null || deviceIp.isBlank()) {
                continue;
            }
            String ip = deviceIp.trim();
            discovered.putIfAbsent(ip, new TapoDevice(ip, "CONFIGURED"));
            log.info("Added configured Tapo device {}", ip);
        }
    }


    private int discoverViaSubnetScan(Map<String, TapoDevice> discovered, List<DiscoveryInterface> interfaces) {
        int discoveredBefore = discovered.size();
        ConcurrentHashMap<String, TapoDevice> scanned = new ConcurrentHashMap<>();

        int totalHosts = interfaces.size() * 254;
        CountDownLatch latch = new CountDownLatch(totalHosts);
        ExecutorService executor = Executors.newFixedThreadPool(SCAN_THREAD_POOL_SIZE);

        log.info("Scanning {} IP addresses across {} interface(s) using {} threads...",
                totalHosts, interfaces.size(), SCAN_THREAD_POOL_SIZE);

        for (DiscoveryInterface discoveryInterface : interfaces) {
            byte[] address = discoveryInterface.ipv4().getAddress();
            int first = Byte.toUnsignedInt(address[0]);
            int second = Byte.toUnsignedInt(address[1]);
            int third = Byte.toUnsignedInt(address[2]);

            log.info("Scanning subnet {}.{}.{}.0/24", first, second, third);

            for (int host = 1; host <= 254; host++) {
                String targetIp = first + "." + second + "." + third + "." + host;
                executor.submit(() -> {
                    try {
                        if (isPort80Open(targetIp)) {
                            log.debug("Port 80 open on {}, testing Tapo handshake...", targetIp);
                            if (isLikelyTapoByHandshake(targetIp)) {
                                scanned.putIfAbsent(targetIp, new TapoDevice(targetIp, "UNKNOWN"));
                                log.info("✓ Discovered Tapo device: {}", targetIp);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Error scanning {}: {}", targetIp, ex.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        executor.shutdown();
        try {
            boolean completed = latch.await(SCAN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("Subnet scan did not finish within {} minutes timeout", SCAN_TIMEOUT_MINUTES);
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Subnet scan interrupted");
            executor.shutdownNow();
        }

        scanned.forEach(discovered::putIfAbsent);
        log.info("Subnet scan complete. Scanned {} hosts, found {} Tapo devices",
                totalHosts, scanned.size());
        return discovered.size() - discoveredBefore;
    }

    private boolean isPort80Open(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, 80), SCAN_CONNECT_TIMEOUT_MS);
            return true;
        } catch (ConnectException ignored) {
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isLikelyTapoByHandshake(String ip) {
        try {
            klapProtocolClient.handshake(ip, tapoProperties.getEmail(), tapoProperties.getPassword());
            log.debug("KLAP handshake successful for {}", ip);
            return true;
        } catch (TapoException ex) {
            log.debug("KLAP handshake failed for {}: {}", ip, ex.getMessage());
            return false;
        }
    }


    private List<DiscoveryInterface> resolveDiscoveryInterfaces() {
        List<DiscoveryInterface> physical = new ArrayList<>();
        List<DiscoveryInterface> fallback = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return List.of();
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                List<DiscoveryInterface> candidates = extractInterfaceCandidates(networkInterface);
                if (candidates.isEmpty()) {
                    log.info("Skipping interface {} ({}): no IPv4 broadcast address",
                            networkInterface.getName(), networkInterface.getDisplayName());
                } else {
                    fallback.addAll(candidates);
                }

                if (!isPhysicalCandidate(networkInterface)) {
                    log.info("Skipping interface {} ({}): filtered as virtual/non-physical",
                            networkInterface.getName(), networkInterface.getDisplayName());
                    continue;
                }

                physical.addAll(candidates);
                log.info("Using interface {} ({}) for Tapo discovery",
                        networkInterface.getName(), networkInterface.getDisplayName());
            }
        } catch (SocketException ex) {
            log.warn("Could not resolve network interfaces for Tapo discovery: {}", ex.getMessage());
            return List.of();
        }

        if (!physical.isEmpty()) {
            return physical;
        }

        if (!fallback.isEmpty()) {
            log.warn("No physical interfaces matched filter, falling back to all non-loopback interfaces with broadcast");
            return fallback;
        }

        log.warn("No usable network interfaces found for Tapo discovery");
        return List.of();
    }

    private List<DiscoveryInterface> extractInterfaceCandidates(NetworkInterface networkInterface) throws SocketException {
        if (!networkInterface.isUp() || networkInterface.isLoopback()) {
            return List.of();
        }

        List<DiscoveryInterface> result = new ArrayList<>();
        Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address != null && address.getAddress().length == 4) {
                result.add(new DiscoveryInterface(networkInterface.getName(), address));
            }
        }
        return result;
    }

    private boolean isPhysicalCandidate(NetworkInterface networkInterface) throws SocketException {
        if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
            return false;
        }

        String display = networkInterface.getDisplayName();
        String lowered = display == null ? "" : display.toLowerCase(Locale.ROOT);
        for (String keyword : EXCLUDED_INTERFACE_KEYWORDS) {
            if (lowered.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private record DiscoveryInterface(String name, InetAddress ipv4) {
    }
}
