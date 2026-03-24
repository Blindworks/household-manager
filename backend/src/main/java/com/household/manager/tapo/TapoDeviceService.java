package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TapoDeviceService {

    private static final long LOCAL_DISCOVERY_CACHE_TTL_MS = 300_000; // 5 minutes

    private final TapoCloudService tapoCloudService;
    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceFactory tapoDeviceFactory;
    private final TapoProperties tapoProperties;

    private final Map<String, TapoLocalDeviceConnection> localConnectionCache = new ConcurrentHashMap<>();
    private final Map<String, TapoDiscoveryDevice> localDeviceCache = new ConcurrentHashMap<>();
    private volatile Instant localDiscoveryCachedAt;

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
        List<TapoDiscoveryDevice> devices = tapoDiscoveryService.discoverLocalDevices(tapoProperties, tapoDeviceFactory);
        updateLocalDeviceCache(devices);
        return devices;
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, null, null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        String resolvedIp = ipAddress;
        TapoAuthProtocol resolvedProtocol = protocol;

        // If no IP provided, try to resolve from local discovery cache
        if (resolvedIp == null || resolvedIp.isBlank()) {
            TapoDiscoveryDevice localDevice = resolveLocalDevice(deviceId);
            if (localDevice != null) {
                resolvedIp = localDevice.ipAddress();
                resolvedProtocol = localDevice.authProtocol();
            }
        }

        if (resolvedIp != null && !resolvedIp.isBlank()) {
            try {
                TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, resolvedIp, resolvedProtocol);
                JsonNode deviceInfo = connection.getDeviceInfo();
                log.debug("Tapo-Geraet {} lokal erreicht ({})", deviceId, resolvedIp);
                return TapoDeviceState.from(deviceInfo, null, tapoCloudService);
            } catch (Exception ex) {
                log.debug("Lokale Verbindung zu {} ({}) fehlgeschlagen: {}, versuche Cloud", deviceId, resolvedIp, ex.getMessage());
                localConnectionCache.remove(deviceId);
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
        if (setDevicePoweredLocalFirst(deviceId, ipAddress, protocol, true)) {
            return;
        }
        tapoCloudService.setDevicePowered(deviceId, true);
        log.info("Tapo device switched on via cloud (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        turnOff(deviceId, null, null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (setDevicePoweredLocalFirst(deviceId, ipAddress, protocol, false)) {
            return;
        }
        tapoCloudService.setDevicePowered(deviceId, false);
        log.info("Tapo device switched off via cloud (deviceId={})", deviceId);
    }

    public JsonNode getEnergyUsage(String deviceId) {
        return tapoCloudService.getEnergyUsage(deviceId);
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
    }

    private TapoDiscoveryDevice resolveLocalDevice(String deviceId) {
        ensureLocalDiscoveryCache();
        return localDeviceCache.get(deviceId);
    }

    private synchronized void ensureLocalDiscoveryCache() {
        if (localDiscoveryCachedAt != null
                && Instant.now().isBefore(localDiscoveryCachedAt.plusMillis(LOCAL_DISCOVERY_CACHE_TTL_MS))) {
            return;
        }
        try {
            log.info("Starte lokale Tapo-Discovery fuer IP-Aufloesung");
            List<TapoDiscoveryDevice> devices = tapoDiscoveryService.discoverLocalDevices(tapoProperties, tapoDeviceFactory);
            updateLocalDeviceCache(devices);
            log.info("Lokale Discovery abgeschlossen: {} Geraete gefunden", devices.size());
        } catch (Exception ex) {
            log.warn("Lokale Tapo-Discovery fehlgeschlagen: {}", ex.getMessage());
            localDiscoveryCachedAt = Instant.now(); // Don't retry immediately
        }
    }

    private void updateLocalDeviceCache(List<TapoDiscoveryDevice> devices) {
        localDeviceCache.clear();
        for (TapoDiscoveryDevice device : devices) {
            if (device.deviceId() != null && device.ipAddress() != null) {
                localDeviceCache.put(device.deviceId(), device);
                getOrCreateLocalConnection(device.deviceId(), device.ipAddress(), device.authProtocol());
            }
        }
        localDiscoveryCachedAt = Instant.now();
    }

    private TapoLocalDeviceConnection getOrCreateLocalConnection(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        return localConnectionCache.computeIfAbsent(deviceId, id -> {
            TapoAuthProtocol effectiveProtocol = protocol != null ? protocol : TapoAuthProtocol.KLAP;
            log.debug("Erstelle lokale Verbindung fuer {} ({}, {})", deviceId, ipAddress, effectiveProtocol);
            return tapoDeviceFactory.create(effectiveProtocol, ipAddress, tapoProperties.getEmail(), tapoProperties.getPassword());
        });
    }

    private boolean setDevicePoweredLocalFirst(String deviceId, String ipAddress, TapoAuthProtocol protocol, boolean poweredOn) {
        String resolvedIp = ipAddress;
        TapoAuthProtocol resolvedProtocol = protocol;

        if (resolvedIp == null || resolvedIp.isBlank()) {
            TapoDiscoveryDevice localDevice = resolveLocalDevice(deviceId);
            if (localDevice != null) {
                resolvedIp = localDevice.ipAddress();
                resolvedProtocol = localDevice.authProtocol();
            }
        }

        if (resolvedIp == null || resolvedIp.isBlank()) {
            return false;
        }
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, resolvedIp, resolvedProtocol);
            connection.setDevicePowered(poweredOn);
            log.info("Tapo device switched {} locally (deviceId={}, ip={})", poweredOn ? "on" : "off", deviceId, resolvedIp);
            return true;
        } catch (Exception ex) {
            log.debug("Lokale Steuerung fuer {} ({}) fehlgeschlagen: {}, versuche Cloud", deviceId, resolvedIp, ex.getMessage());
            localConnectionCache.remove(deviceId);
            return false;
        }
    }
}
