package com.household.manager.tapo.service;

import com.household.manager.tapo.config.TapoProperties;
import com.household.manager.tapo.model.TapoDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDiscoveryService {

    private static final int TAPO_DISCOVERY_PORT = 20002;
    private static final int RECEIVE_BUFFER_SIZE = 4096;
    private static final int SCAN_THREAD_COUNT = 50;
    private static final int SCAN_CONNECT_TIMEOUT_MS = 500;
    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
    private static final List<String> EXCLUDED_INTERFACE_KEYWORDS = List.of(
            "virtual",
            "vethernet",
            "wsl",
            "hyper-v",
            "docker"
    );

    private final TapoProperties tapoProperties;
    private final RestTemplate restTemplate;

    public List<TapoDevice> discoverDevices() {
        Map<String, TapoDevice> discovered = new LinkedHashMap<>();
        addConfiguredDevices(discovered);

        List<DiscoveryInterface> interfaces = resolveDiscoveryInterfaces();
        int udpFound = discoverViaUdpBroadcast(discovered, interfaces);
        int subnetCandidates = 0;
        int deduplicatedNew = 0;

        if (udpFound == 0) {
            ScanStats stats = discoverViaSubnetScan(discovered, interfaces);
            subnetCandidates = stats.candidates();
            deduplicatedNew = stats.newDevices();
            log.info("Tapo subnet scan finished, found {} additional device(s)", deduplicatedNew);
        }

        List<TapoDevice> result = new ArrayList<>(discovered.values());
        log.info("Tapo discovery finished, found {} device(s) total", result.size());
        log.info("Tapo discovery summary: udpFound={}, subnetCandidates={}, deduplicatedNew={}, total={}",
                udpFound, subnetCandidates, deduplicatedNew, result.size());
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

    private int discoverViaUdpBroadcast(Map<String, TapoDevice> discovered, List<DiscoveryInterface> interfaces) {
        byte[] requestPayload = "{\"method\":\"get_device_info\"}".getBytes(StandardCharsets.UTF_8);
        int discoveredBefore = discovered.size();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (DiscoveryInterface discoveryInterface : interfaces) {
                DatagramPacket request = new DatagramPacket(
                        requestPayload,
                        requestPayload.length,
                        discoveryInterface.broadcast(),
                        TAPO_DISCOVERY_PORT
                );
                socket.send(request);
                log.info("Sent Tapo discovery broadcast on {}:{} via {} ({})",
                        discoveryInterface.broadcast().getHostAddress(),
                        TAPO_DISCOVERY_PORT,
                        discoveryInterface.name(),
                        discoveryInterface.ipv4().getHostAddress());
            }

            long deadline = System.currentTimeMillis() + tapoProperties.getDiscoveryTimeoutMs();
            while (System.currentTimeMillis() < deadline) {
                int remainingMs = (int) Math.max(1, deadline - System.currentTimeMillis());
                socket.setSoTimeout(remainingMs);

                byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException ex) {
                    break;
                }

                String ip = response.getAddress().getHostAddress();
                String payload = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                discovered.putIfAbsent(ip, new TapoDevice(ip, detectDeviceType(payload)));
                log.info("Discovered Tapo device candidate {} via UDP broadcast", ip);
            }
        } catch (IOException ex) {
            log.warn("Tapo UDP discovery failed: {}", ex.getMessage());
        }

        int udpFound = discovered.size() - discoveredBefore;
        log.info("Tapo UDP discovery finished, found {} device(s)", udpFound);
        return udpFound;
    }

    private ScanStats discoverViaSubnetScan(Map<String, TapoDevice> discovered, List<DiscoveryInterface> interfaces) {
        log.info("No devices found via UDP broadcast, starting subnet scan fallback");
        int discoveredBefore = discovered.size();
        AtomicInteger candidates = new AtomicInteger(0);

        ConcurrentMap<String, TapoDevice> scanned = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(SCAN_THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(interfaces.size() * 254);

        for (DiscoveryInterface discoveryInterface : interfaces) {
            byte[] address = discoveryInterface.ipv4().getAddress();
            int first = Byte.toUnsignedInt(address[0]);
            int second = Byte.toUnsignedInt(address[1]);
            int third = Byte.toUnsignedInt(address[2]);

            for (int host = 1; host <= 254; host++) {
                String targetIp = first + "." + second + "." + third + "." + host;
                executor.submit(() -> {
                    try {
                        if (!isPort80Open(targetIp)) {
                            return;
                        }
                        if (isLikelyTapoByHandshake(targetIp)) {
                            candidates.incrementAndGet();
                            scanned.putIfAbsent(targetIp, new TapoDevice(targetIp, "UNKNOWN"));
                            log.info("Discovered Tapo device candidate {} via subnet scan", targetIp);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        executor.shutdown();
        try {
            boolean completed = latch.await(2, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("Subnet scan did not finish within timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Subnet scan interrupted");
        }

        scanned.forEach(discovered::putIfAbsent);
        int newDevices = discovered.size() - discoveredBefore;
        return new ScanStats(candidates.get(), newDevices);
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
            Map<String, Object> request = Map.of(
                    "method", "handshake",
                    "params", Map.of("key", "invalid-key")
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject("http://" + ip + "/app", request, Map.class);
            if (response == null) {
                return false;
            }

            Object errorCode = response.get("error_code");
            Object result = response.get("result");
            return errorCode != null || result != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private String detectDeviceType(String responsePayload) {
        Matcher matcher = MODEL_PATTERN.matcher(responsePayload);
        if (matcher.find()) {
            return matcher.group(1);
        }

        String lower = responsePayload.toLowerCase(Locale.ROOT);
        if (lower.contains("p110")) {
            return "P110";
        }
        if (lower.contains("p100")) {
            return "P100";
        }
        if (lower.contains("l510")) {
            return "L510";
        }
        if (lower.contains("l530")) {
            return "L530";
        }
        return "UNKNOWN";
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
        for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
            InetAddress address = interfaceAddress.getAddress();
            InetAddress broadcast = interfaceAddress.getBroadcast();
            if (address == null || broadcast == null) {
                continue;
            }
            if (address.getAddress().length != 4 || broadcast.getAddress().length != 4) {
                continue;
            }
            result.add(new DiscoveryInterface(networkInterface.getName(), address, broadcast));
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

    private record DiscoveryInterface(String name, InetAddress ipv4, InetAddress broadcast) {
    }

    private record ScanStats(int candidates, int newDevices) {
    }
}
