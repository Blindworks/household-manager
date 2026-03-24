package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level Tapo device service.
 * <p>
 * Control priority:
 * 1. Local KLAP protocol (most devices with current firmware)
 * 2. Local AES protocol (fallback for older firmware)
 * 3. V2 Cloud API (last resort)
 * <p>
 * On local failure, the service automatically retries with the other protocol
 * and caches the working protocol for subsequent requests.
 */
@Service
@Slf4j
public class TapoDeviceService {

    private final TapoCloudService tapoCloudService;
    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceFactory tapoDeviceFactory;
    private final TapoProperties tapoProperties;

    private final Map<String, TapoLocalDeviceConnection> localConnectionCache = new ConcurrentHashMap<>();
    private final Map<String, TapoAuthProtocol> workingProtocolCache = new ConcurrentHashMap<>();

    public TapoDeviceService(TapoCloudService tapoCloudService,
                             TapoDiscoveryService tapoDiscoveryService,
                             TapoDeviceFactory tapoDeviceFactory,
                             TapoProperties tapoProperties) {
        this.tapoCloudService = tapoCloudService;
        this.tapoDiscoveryService = tapoDiscoveryService;
        this.tapoDeviceFactory = tapoDeviceFactory;
        this.tapoProperties = tapoProperties;
    }

    public List<TapoCloudDevice> discoverCloudDevices() {
        return tapoCloudService.getTapoDevices(true);
    }

    public List<TapoDiscoveryDevice> discoverLocalDevices() {
        List<TapoDiscoveryDevice> devices = new java.util.ArrayList<>();

        // 1. UDP broadcast discovery
        try {
            List<TapoDiscoveryDevice> udpDevices = tapoDiscoveryService.discoverLocalDevices(tapoProperties, tapoDeviceFactory);
            devices.addAll(udpDevices);
        } catch (Exception ex) {
            log.debug("UDP-Discovery fehlgeschlagen: {}", ex.getMessage());
        }

        // 2. Static device config (for Docker / firewalled environments)
        java.util.Set<String> discoveredIps = devices.stream()
                .map(TapoDiscoveryDevice::ipAddress)
                .collect(java.util.stream.Collectors.toSet());

        for (TapoProperties.TapoDeviceConfig config : tapoProperties.getDevices()) {
            if (config.getIp() == null || config.getIp().isBlank()) continue;
            if (discoveredIps.contains(config.getIp())) continue;

            try {
                TapoDiscoveryDevice device = probeStaticDevice(config);
                if (device != null) {
                    devices.add(device);
                    log.info("Statisch konfiguriertes Tapo-Geraet gefunden: {} ({})", config.getName(), config.getIp());
                }
            } catch (Exception ex) {
                log.debug("Statisches Geraet {} ({}) nicht erreichbar: {}", config.getName(), config.getIp(), ex.getMessage());
            }
        }

        for (TapoDiscoveryDevice device : devices) {
            if (device.deviceId() != null && device.ipAddress() != null) {
                workingProtocolCache.put(device.deviceId(), device.authProtocol());
                getOrCreateLocalConnection(device.deviceId(), device.ipAddress(), device.authProtocol());
            }
        }
        return devices;
    }

    /**
     * Probe a statically configured device by trying KLAP, then AES.
     */
    private TapoDiscoveryDevice probeStaticDevice(TapoProperties.TapoDeviceConfig config) {
        String ip = config.getIp();
        for (TapoAuthProtocol protocol : new TapoAuthProtocol[]{TapoAuthProtocol.KLAP, TapoAuthProtocol.AES}) {
            try {
                TapoLocalDeviceConnection conn = tapoDeviceFactory.create(protocol, ip,
                        tapoProperties.getEmail(), tapoProperties.getPassword());
                JsonNode info = conn.getDeviceInfo();
                String deviceId = config.getDeviceId();
                if (deviceId == null || deviceId.isBlank()) {
                    deviceId = firstText(info, "device_id", "deviceId");
                }
                return new TapoDiscoveryDevice(
                        ip, protocol, deviceId,
                        firstText(info, "model", "device_model"),
                        firstText(info, "nickname", "alias"),
                        info.path("device_on").asBoolean(false)
                );
            } catch (Exception ex) {
                log.debug("Probe {} mit {} fuer {} fehlgeschlagen: {}", ip, protocol, config.getName(), ex.getMessage());
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, null, null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                JsonNode deviceInfo = executeLocalWithFallback(deviceId, ipAddress, protocol,
                        TapoLocalDeviceConnection::getDeviceInfo);
                log.debug("Tapo-Geraet {} lokal erreicht ({})", deviceId, ipAddress);
                return TapoDeviceState.fromLocal(deviceInfo, tapoCloudService);
            } catch (Exception ex) {
                log.debug("Lokale Verbindung zu {} ({}) fehlgeschlagen: {}, versuche V2 Cloud",
                        deviceId, ipAddress, ex.getMessage());
            }
        }

        TapoCloudDevice cloudDevice = tapoCloudService.findDeviceById(deviceId);
        JsonNode deviceInfo = tapoCloudService.getDeviceInfo(deviceId);
        return TapoDeviceState.from(deviceInfo, cloudDevice, tapoCloudService);
    }

    public void turnOn(String deviceId) {
        turnOn(deviceId, null, null);
    }

    public void turnOn(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                executeLocalWithFallback(deviceId, ipAddress, protocol,
                        conn -> { conn.setDevicePowered(true); return null; });
                log.info("Tapo device switched on locally (deviceId={}, ip={})", deviceId, ipAddress);
                return;
            } catch (Exception ex) {
                log.debug("Lokale Steuerung fuer {} ({}) fehlgeschlagen: {}, versuche V2 Cloud",
                        deviceId, ipAddress, ex.getMessage());
            }
        }
        tapoCloudService.setDevicePowered(deviceId, true);
        log.info("Tapo device switched on via V2 Cloud (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        turnOff(deviceId, null, null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                executeLocalWithFallback(deviceId, ipAddress, protocol,
                        conn -> { conn.setDevicePowered(false); return null; });
                log.info("Tapo device switched off locally (deviceId={}, ip={})", deviceId, ipAddress);
                return;
            } catch (Exception ex) {
                log.debug("Lokale Steuerung fuer {} ({}) fehlgeschlagen: {}, versuche V2 Cloud",
                        deviceId, ipAddress, ex.getMessage());
            }
        }
        tapoCloudService.setDevicePowered(deviceId, false);
        log.info("Tapo device switched off via V2 Cloud (deviceId={})", deviceId);
    }

    public JsonNode getEnergyUsage(String deviceId) {
        return getEnergyUsage(deviceId, null, null);
    }

    public JsonNode getEnergyUsage(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                return executeLocalWithFallback(deviceId, ipAddress, protocol,
                        TapoLocalDeviceConnection::getEnergyUsage);
            } catch (Exception ex) {
                log.debug("Lokaler Energieverbrauch fuer {} fehlgeschlagen: {}, versuche Cloud",
                        deviceId, ex.getMessage());
            }
        }
        return tapoCloudService.getEnergyUsage(deviceId);
    }

    public JsonNode getCurrentPower(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            return executeLocalWithFallback(deviceId, ipAddress, protocol,
                    TapoLocalDeviceConnection::getCurrentPower);
        }
        throw new TapoException("getCurrentPower benoetigt eine lokale IP-Adresse.");
    }

    public String decodeAlias(String alias) {
        return tapoCloudService.decodeAlias(alias);
    }

    public Map<String, Object> buildMetadata(TapoCloudDevice device) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("aliasRaw", device.alias());
        metadata.put("deviceName", device.deviceName());
        metadata.put("deviceType", device.deviceType());
        metadata.put("deviceHwVer", device.deviceHwVer());
        metadata.put("deviceMac", device.deviceMac());
        metadata.put("fwVer", device.fwVer());
        metadata.put("appServerUrl", device.appServerUrl());
        metadata.put("cloudStatus", device.status());
        metadata.put("role", device.role());
        return metadata;
    }

    public void clearLocalConnection(String deviceId) {
        localConnectionCache.remove(deviceId);
        workingProtocolCache.remove(deviceId);
    }

    /**
     * Try the preferred protocol first, then fall back to the alternative protocol.
     * Caches the working protocol for future requests.
     */
    private JsonNode executeLocalWithFallback(String deviceId, String ipAddress,
                                               TapoAuthProtocol protocol,
                                               LocalDeviceAction action) {
        TapoAuthProtocol preferred = resolveProtocol(deviceId, protocol);
        TapoAuthProtocol alternative = preferred == TapoAuthProtocol.KLAP
                ? TapoAuthProtocol.AES : TapoAuthProtocol.KLAP;

        // Try preferred protocol
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, preferred);
            JsonNode result = action.execute(connection);
            workingProtocolCache.put(deviceId, preferred);
            return result;
        } catch (Exception ex) {
            log.debug("{}-Protokoll fuer {} fehlgeschlagen: {}, versuche {}",
                    preferred, deviceId, ex.getMessage(), alternative);
            localConnectionCache.remove(deviceId);
        }

        // Try alternative protocol
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, alternative);
            JsonNode result = action.execute(connection);
            workingProtocolCache.put(deviceId, alternative);
            log.info("Tapo device {} funktioniert mit {} (statt {})", deviceId, alternative, preferred);
            return result;
        } catch (Exception ex) {
            localConnectionCache.remove(deviceId);
            throw new TapoException("Lokale Steuerung fuer " + deviceId + " mit beiden Protokollen fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private TapoAuthProtocol resolveProtocol(String deviceId, TapoAuthProtocol requested) {
        if (requested != null && requested != TapoAuthProtocol.UNKNOWN) {
            return requested;
        }
        TapoAuthProtocol cached = workingProtocolCache.get(deviceId);
        if (cached != null && cached != TapoAuthProtocol.UNKNOWN) {
            return cached;
        }
        return TapoAuthProtocol.KLAP;
    }

    private TapoLocalDeviceConnection getOrCreateLocalConnection(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        String cacheKey = deviceId + ":" + protocol.name();
        return localConnectionCache.computeIfAbsent(cacheKey, id -> {
            log.debug("Erstelle lokale Verbindung fuer {} ({}, {})", deviceId, ipAddress, protocol);
            return tapoDeviceFactory.create(protocol, ipAddress, tapoProperties.getEmail(), tapoProperties.getPassword());
        });
    }

    @FunctionalInterface
    private interface LocalDeviceAction {
        JsonNode execute(TapoLocalDeviceConnection connection);
    }
}
