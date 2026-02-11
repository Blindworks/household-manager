package com.household.manager.tapo;

import com.household.manager.tapo.dto.TapoDiscoveryDto;
import com.household.manager.tapo.exception.TapoCommunicationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class TapoDiscoveryService {

    private static final String DNS_SD_SERVICE_TYPES = "_services._dns-sd._udp.local.";
    private static final List<String> TAPO_SERVICE_TYPES = List.of(
            "_tp-link._tcp.local.",
            "_tapo._tcp.local.",
            "_tplink._tcp.local.",
            "_smartplug._tcp.local."
    );
    private static final List<String> TAPO_SERVICE_KEYWORDS = List.of("tapo", "tp-link", "tplink", "p110", "kl110", "smartplug");
    private static final int SERVICE_INFO_TIMEOUT_MS = 750;
    private static final int DISCOVERY_POLL_INTERVAL_MS = 250;
    private static final int DISCOVERY_GRACE_PERIOD_MS = 1000;

    @Value("${tapo.discovery.timeout-ms:3000}")
    private int discoveryTimeoutMs;

    public List<TapoDiscoveryDto> discoverTapoDevices() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<TapoDiscoveryDto>> discoveryTask = executor.submit(this::discoverInternal);

        try {
            return discoveryTask.get(discoveryTimeoutMs + DISCOVERY_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("Tapo discovery timed out after {} ms", discoveryTimeoutMs + DISCOVERY_GRACE_PERIOD_MS, ex);
            discoveryTask.cancel(true);
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Tapo discovery interrupted");
            return List.of();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new TapoCommunicationException("Failed to discover Tapo devices over mDNS", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<TapoDiscoveryDto> discoverInternal() {
        Map<String, TapoDiscoveryDto> devicesByKey = new ConcurrentHashMap<>();
        List<JmDNS> jmdnsInstances = new ArrayList<>();
        Map<JmDNS, Map<String, ServiceListener>> listenersByInstance = new LinkedHashMap<>();

        try {
            jmdnsInstances.addAll(createJmDnsInstances());
            for (JmDNS jmdns : jmdnsInstances) {
                listenersByInstance.put(jmdns, registerSeedListeners(jmdns, devicesByKey));
            }

            log.info("Starting Tapo mDNS discovery for {} ms on {} instance(s), seed service types={}",
                    discoveryTimeoutMs, jmdnsInstances.size(), TAPO_SERVICE_TYPES);
            waitAndDiscoverAdditionalTypes(jmdnsInstances, listenersByInstance, devicesByKey);
            return asStableList(devicesByKey);
        } catch (IOException ex) {
            throw new TapoCommunicationException("Failed to initialize mDNS discovery for Tapo devices", ex);
        } finally {
            closeDiscoveryResources(jmdnsInstances, listenersByInstance);
        }
    }

    private Map<String, ServiceListener> registerSeedListeners(JmDNS jmdns, Map<String, TapoDiscoveryDto> devicesByKey) {
        Map<String, ServiceListener> listeners = new LinkedHashMap<>();
        for (String serviceType : TAPO_SERVICE_TYPES) {
            registerListener(jmdns, listeners, serviceType, devicesByKey);
        }
        return listeners;
    }

    private void waitAndDiscoverAdditionalTypes(
            List<JmDNS> jmdnsInstances,
            Map<JmDNS, Map<String, ServiceListener>> listenersByInstance,
            Map<String, TapoDiscoveryDto> devicesByKey) {
        long deadline = System.currentTimeMillis() + discoveryTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.warn("Tapo mDNS discovery interrupted before timeout");
                return;
            }

            for (JmDNS jmdns : jmdnsInstances) {
                discoverAdditionalServiceTypes(jmdns, listenersByInstance.get(jmdns), devicesByKey);
            }

            try {
                Thread.sleep(DISCOVERY_POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Tapo mDNS discovery interrupted while polling");
                return;
            }
        }
    }

    private void discoverAdditionalServiceTypes(
            JmDNS jmdns,
            Map<String, ServiceListener> listeners,
            Map<String, TapoDiscoveryDto> devicesByKey) {
        try {
            ServiceInfo[] availableTypes = jmdns.list(DNS_SD_SERVICE_TYPES, SERVICE_INFO_TIMEOUT_MS);
            for (ServiceInfo typeInfo : availableTypes) {
                String discoveredType = normalizeServiceType(typeInfo.getName());
                if (discoveredType == null || !shouldTrackServiceType(discoveredType) || listeners.containsKey(discoveredType)) {
                    continue;
                }

                registerListener(jmdns, listeners, discoveredType, devicesByKey);
                log.info("Added dynamic mDNS service type for Tapo discovery: {}", discoveredType);
            }
        } catch (Exception ex) {
            log.debug("Could not inspect dynamic mDNS service types: {}", ex.getMessage());
        }
    }

    private void registerListener(
            JmDNS jmdns,
            Map<String, ServiceListener> listeners,
            String serviceType,
            Map<String, TapoDiscoveryDto> devicesByKey) {
        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                log.debug("Tapo mDNS service added: type={}, name={}", event.getType(), event.getName());
                jmdns.requestServiceInfo(event.getType(), event.getName(), SERVICE_INFO_TIMEOUT_MS);
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                log.debug("Tapo mDNS service removed: type={}, name={}", event.getType(), event.getName());
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                captureServiceInfo(event.getInfo(), event.getType(), devicesByKey);
            }
        };

        jmdns.addServiceListener(serviceType, listener);
        listeners.put(serviceType, listener);
    }

    private void captureServiceInfo(ServiceInfo info, String serviceType, Map<String, TapoDiscoveryDto> devicesByKey) {
        if (info == null) {
            return;
        }
        if (!isLikelyTapo(info, serviceType)) {
            return;
        }

        String ip = resolveIp(info);
        if (ip == null) {
            log.debug("Skipping Tapo mDNS service without IP: {}", info.getQualifiedName());
            return;
        }

        TapoDiscoveryDto dto = new TapoDiscoveryDto();
        dto.setIp(ip);
        dto.setHostname(cleanHostname(info.getServer()));
        dto.setPort(info.getPort());
        dto.setServiceName(info.getName());

        String key = dto.getIp() + "|" + dto.getServiceName();
        devicesByKey.put(key, dto);

        log.info("Discovered Tapo service: ip={}, host={}, port={}, service={}, type={}",
                dto.getIp(),
                dto.getHostname(),
                dto.getPort(),
                dto.getServiceName(),
                normalizeServiceType(serviceType));
    }

    private boolean isLikelyTapo(ServiceInfo info, String serviceType) {
        String normalizedType = normalizeServiceType(serviceType);
        if (normalizedType != null && TAPO_SERVICE_TYPES.contains(normalizedType)) {
            return true;
        }

        StringBuilder haystack = new StringBuilder();
        if (normalizedType != null) {
            haystack.append(normalizedType).append(' ');
        }
        appendIfPresent(haystack, info.getName());
        appendIfPresent(haystack, info.getQualifiedName());
        appendIfPresent(haystack, info.getServer());
        appendIfPresent(haystack, info.getApplication());

        String value = haystack.toString().toLowerCase(Locale.ROOT);
        return TAPO_SERVICE_KEYWORDS.stream().anyMatch(value::contains);
    }

    private void appendIfPresent(StringBuilder buffer, String value) {
        if (value != null && !value.isBlank()) {
            buffer.append(value).append(' ');
        }
    }

    private boolean shouldTrackServiceType(String serviceType) {
        if (TAPO_SERVICE_TYPES.contains(serviceType)) {
            return true;
        }
        return TAPO_SERVICE_KEYWORDS.stream().anyMatch(serviceType::contains);
    }

    private String normalizeServiceType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return null;
        }
        String normalized = serviceType.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".local.") && !normalized.endsWith(".local")) {
            normalized = normalized + ".local.";
        } else if (normalized.endsWith(".local")) {
            normalized = normalized + ".";
        }
        return normalized;
    }

    private List<JmDNS> createJmDnsInstances() throws IOException {
        List<InetAddress> addresses = resolveDiscoveryAddresses();
        if (addresses.isEmpty()) {
            log.warn("No multicast-capable network interface found. Falling back to default JmDNS instance.");
            return List.of(JmDNS.create());
        }

        List<JmDNS> instances = new ArrayList<>();
        for (InetAddress address : addresses) {
            try {
                instances.add(JmDNS.create(address));
                log.debug("Initialized JmDNS for interface address {}", address.getHostAddress());
            } catch (IOException ex) {
                log.warn("Could not initialize JmDNS for {}: {}", address.getHostAddress(), ex.getMessage());
            }
        }

        if (instances.isEmpty()) {
            throw new IOException("Could not initialize JmDNS on any multicast-capable network interface");
        }
        return instances;
    }

    private List<InetAddress> resolveDiscoveryAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return addresses;
            }

            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!isEligibleInterface(networkInterface)) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address);
                    }
                }
            }
        } catch (SocketException ex) {
            log.warn("Could not enumerate network interfaces for Tapo mDNS discovery: {}", ex.getMessage());
        }
        return addresses;
    }

    private boolean isEligibleInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp()
                    && !networkInterface.isLoopback()
                    && !networkInterface.isVirtual()
                    && networkInterface.supportsMulticast();
        } catch (SocketException ex) {
            log.debug("Skipping network interface {}: {}", networkInterface.getName(), ex.getMessage());
            return false;
        }
    }

    private void closeDiscoveryResources(
            List<JmDNS> jmdnsInstances,
            Map<JmDNS, Map<String, ServiceListener>> listenersByInstance) {
        for (JmDNS jmdns : jmdnsInstances) {
            Map<String, ServiceListener> listeners = listenersByInstance.get(jmdns);
            if (listeners != null) {
                listeners.forEach(jmdns::removeServiceListener);
            }
            try {
                jmdns.close();
            } catch (IOException ex) {
                log.debug("Failed to close JmDNS instance cleanly: {}", ex.getMessage());
            }
        }
    }

    private String resolveIp(ServiceInfo info) {
        InetAddress[] ipv4Addresses = info.getInet4Addresses();
        if (ipv4Addresses.length > 0) {
            return ipv4Addresses[0].getHostAddress();
        }

        InetAddress[] allAddresses = info.getInetAddresses();
        if (allAddresses.length > 0) {
            return allAddresses[0].getHostAddress();
        }

        return null;
    }

    private String cleanHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }

        if (hostname.endsWith(".")) {
            return hostname.substring(0, hostname.length() - 1);
        }

        return hostname;
    }

    private List<TapoDiscoveryDto> asStableList(Map<String, TapoDiscoveryDto> devicesByKey) {
        List<TapoDiscoveryDto> results = new ArrayList<>(devicesByKey.values());
        results.sort((left, right) -> {
            String leftKey = left.getIp() + ":" + left.getPort() + ":" + left.getServiceName();
            String rightKey = right.getIp() + ":" + right.getPort() + ":" + right.getServiceName();
            return leftKey.compareTo(rightKey);
        });
        return results;
    }
}
