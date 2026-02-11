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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final List<String> TAPO_SERVICE_TYPES = List.of("_tp-link._tcp.local.", "_tapo._tcp.local.");
    private static final int SERVICE_INFO_TIMEOUT_MS = 750;
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

        try (JmDNS jmdns = JmDNS.create()) {
            Map<String, ServiceListener> listeners = registerListeners(jmdns, devicesByKey);
            log.info("Starting Tapo mDNS discovery for {} ms on service types {}", discoveryTimeoutMs, TAPO_SERVICE_TYPES);

            try {
                Thread.sleep(discoveryTimeoutMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Tapo mDNS discovery interrupted before timeout");
            } finally {
                listeners.forEach(jmdns::removeServiceListener);
            }

            return asStableList(devicesByKey);
        } catch (IOException ex) {
            throw new TapoCommunicationException("Failed to initialize mDNS discovery for Tapo devices", ex);
        }
    }

    private Map<String, ServiceListener> registerListeners(JmDNS jmdns, Map<String, TapoDiscoveryDto> devicesByKey) {
        Map<String, ServiceListener> listeners = new LinkedHashMap<>();

        for (String serviceType : TAPO_SERVICE_TYPES) {
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
                    captureServiceInfo(event.getInfo(), devicesByKey);
                }
            };

            jmdns.addServiceListener(serviceType, listener);
            listeners.put(serviceType, listener);
        }

        return listeners;
    }

    private void captureServiceInfo(ServiceInfo info, Map<String, TapoDiscoveryDto> devicesByKey) {
        if (info == null) {
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

        log.info("Discovered Tapo service: ip={}, host={}, port={}, service={}",
                dto.getIp(),
                dto.getHostname(),
                dto.getPort(),
                dto.getServiceName());
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
