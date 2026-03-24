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
 * 1. tapo-rest sidecar (local LAN via Rust tapo crate) — most reliable
 * 2. Local KLAP/AES connections (Java implementation)
 * 3. V2 Cloud API (fallback)
 */
@Service
@Slf4j
public class TapoDeviceService {

    private final TapoCloudService tapoCloudService;
    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceFactory tapoDeviceFactory;
    private final TapoProperties tapoProperties;
    private final TapoRestClient tapoRestClient;

    private final Map<String, TapoLocalDeviceConnection> localConnectionCache = new ConcurrentHashMap<>();

    public TapoDeviceService(TapoCloudService tapoCloudService,
                             TapoDiscoveryService tapoDiscoveryService,
                             TapoDeviceFactory tapoDeviceFactory,
                             TapoProperties tapoProperties,
                             TapoRestClient tapoRestClient) {
        this.tapoCloudService = tapoCloudService;
        this.tapoDiscoveryService = tapoDiscoveryService;
        this.tapoDeviceFactory = tapoDeviceFactory;
        this.tapoProperties = tapoProperties;
        this.tapoRestClient = tapoRestClient;
    }

    public List<TapoCloudDevice> discoverCloudDevices() {
        return tapoCloudService.getTapoDevices(true);
    }

    public List<TapoDiscoveryDevice> discoverLocalDevices() {
        List<TapoDiscoveryDevice> devices = tapoDiscoveryService.discoverLocalDevices(tapoProperties, tapoDeviceFactory);
        for (TapoDiscoveryDevice device : devices) {
            if (device.deviceId() != null && device.ipAddress() != null) {
                getOrCreateLocalConnection(device.deviceId(), device.ipAddress(), device.authProtocol());
            }
        }
        return devices;
    }

    /**
     * Get the list of devices configured in the tapo-rest sidecar.
     */
    public List<TapoRestClient.TapoRestDevice> discoverTapoRestDevices() {
        if (!tapoRestClient.isAvailable()) {
            return List.of();
        }
        try {
            return tapoRestClient.getDevices();
        } catch (Exception ex) {
            log.warn("tapo-rest Geraeteabfrage fehlgeschlagen: {}", ex.getMessage());
            return List.of();
        }
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, null, null, null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        return getStatus(deviceId, ipAddress, protocol, null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol, String tapoRestName) {
        // 1. Try tapo-rest sidecar first (most reliable)
        if (tapoRestName != null && tapoRestClient.isAvailable()) {
            try {
                JsonNode info = tapoRestClient.getDeviceInfo(tapoRestName);
                if (info != null) {
                    log.debug("Tapo-Geraet {} Status via tapo-rest ('{}') abgerufen", deviceId, tapoRestName);
                    return TapoDeviceState.fromLocal(info, tapoCloudService);
                }
            } catch (Exception ex) {
                log.debug("tapo-rest Status fuer '{}' fehlgeschlagen: {}", tapoRestName, ex.getMessage());
            }
        }

        // 2. Try local KLAP/AES if IP is known
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, protocol);
                JsonNode deviceInfo = connection.getDeviceInfo();
                log.debug("Tapo-Geraet {} lokal erreicht ({})", deviceId, ipAddress);
                return TapoDeviceState.fromLocal(deviceInfo, tapoCloudService);
            } catch (Exception ex) {
                log.debug("Lokale Verbindung zu {} ({}) fehlgeschlagen: {}, versuche V2 Cloud",
                        deviceId, ipAddress, ex.getMessage());
                localConnectionCache.remove(deviceId);
            }
        }

        // 3. V2 Cloud API (fallback)
        TapoCloudDevice cloudDevice = tapoCloudService.findDeviceById(deviceId);
        JsonNode deviceInfo = tapoCloudService.getDeviceInfo(deviceId);
        return TapoDeviceState.from(deviceInfo, cloudDevice, tapoCloudService);
    }

    public void turnOn(String deviceId) {
        turnOn(deviceId, null, null, null);
    }

    public void turnOn(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        turnOn(deviceId, ipAddress, protocol, null);
    }

    public void turnOn(String deviceId, String ipAddress, TapoAuthProtocol protocol, String tapoRestName) {
        // 1. Try tapo-rest first
        if (tapoRestName != null && tryTapoRest(tapoRestName, true)) {
            return;
        }
        // 2. Try local KLAP/AES
        if (setDevicePoweredLocalFirst(deviceId, ipAddress, protocol, true)) {
            return;
        }
        // 3. Cloud fallback
        tapoCloudService.setDevicePowered(deviceId, true);
        log.info("Tapo device switched on via V2 Cloud (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        turnOff(deviceId, null, null, null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        turnOff(deviceId, ipAddress, protocol, null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol, String tapoRestName) {
        // 1. Try tapo-rest first
        if (tapoRestName != null && tryTapoRest(tapoRestName, false)) {
            return;
        }
        // 2. Try local KLAP/AES
        if (setDevicePoweredLocalFirst(deviceId, ipAddress, protocol, false)) {
            return;
        }
        // 3. Cloud fallback
        tapoCloudService.setDevicePowered(deviceId, false);
        log.info("Tapo device switched off via V2 Cloud (deviceId={})", deviceId);
    }

    public JsonNode getEnergyUsage(String deviceId) {
        return getEnergyUsage(deviceId, null);
    }

    public JsonNode getEnergyUsage(String deviceId, String tapoRestName) {
        if (tapoRestName != null && tapoRestClient.isAvailable()) {
            try {
                JsonNode energy = tapoRestClient.getEnergyUsage(tapoRestName);
                if (energy != null) {
                    return energy;
                }
            } catch (Exception ex) {
                log.debug("tapo-rest Energieverbrauch fuer '{}' fehlgeschlagen: {}", tapoRestName, ex.getMessage());
            }
        }
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

    private boolean tryTapoRest(String tapoRestName, boolean poweredOn) {
        if (!tapoRestClient.isAvailable()) {
            return false;
        }
        try {
            if (poweredOn) {
                tapoRestClient.turnOn(tapoRestName);
            } else {
                tapoRestClient.turnOff(tapoRestName);
            }
            return true;
        } catch (Exception ex) {
            log.debug("tapo-rest Steuerung fuer '{}' fehlgeschlagen: {}, versuche naechste Methode",
                    tapoRestName, ex.getMessage());
            return false;
        }
    }

    private TapoLocalDeviceConnection getOrCreateLocalConnection(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        return localConnectionCache.computeIfAbsent(deviceId, id -> {
            TapoAuthProtocol effectiveProtocol = protocol != null ? protocol : TapoAuthProtocol.KLAP;
            log.debug("Erstelle lokale Verbindung fuer {} ({}, {})", deviceId, ipAddress, effectiveProtocol);
            return tapoDeviceFactory.create(effectiveProtocol, ipAddress, tapoProperties.getEmail(), tapoProperties.getPassword());
        });
    }

    private boolean setDevicePoweredLocalFirst(String deviceId, String ipAddress, TapoAuthProtocol protocol, boolean poweredOn) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, protocol);
            connection.setDevicePowered(poweredOn);
            log.info("Tapo device switched {} locally (deviceId={}, ip={})", poweredOn ? "on" : "off", deviceId, ipAddress);
            return true;
        } catch (Exception ex) {
            log.debug("Lokale Steuerung fuer {} ({}) fehlgeschlagen: {}, versuche V2 Cloud",
                    deviceId, ipAddress, ex.getMessage());
            localConnectionCache.remove(deviceId);
            return false;
        }
    }
}
