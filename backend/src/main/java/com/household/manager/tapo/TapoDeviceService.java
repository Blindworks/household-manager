package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TapoDeviceService {

    private final TapoCloudService tapoCloudService;
    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceFactory tapoDeviceFactory;
    private final TapoProperties tapoProperties;

    private final Map<String, TapoLocalDeviceConnection> localConnectionCache = new ConcurrentHashMap<>();

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
        for (TapoDiscoveryDevice device : devices) {
            if (device.deviceId() != null && device.ipAddress() != null) {
                getOrCreateLocalConnection(device.deviceId(), device.ipAddress(), device.authProtocol());
            }
        }
        return devices;
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, null, null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        // Try local control first if IP is known
        if (ipAddress != null && !ipAddress.isBlank()) {
            try {
                TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, protocol);
                JsonNode deviceInfo = connection.getDeviceInfo();
                log.debug("Tapo-Geraet {} lokal erreicht ({})", deviceId, ipAddress);
                return TapoDeviceState.from(deviceInfo, null, tapoCloudService);
            } catch (Exception ex) {
                log.debug("Lokale Verbindung zu {} ({}) fehlgeschlagen: {}, versuche Cloud", deviceId, ipAddress, ex.getMessage());
                localConnectionCache.remove(deviceId);
            }
        }

        // Cloud control (like the Tapo app does remotely)
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
            log.debug("Lokale Steuerung fuer {} ({}) fehlgeschlagen: {}, versuche Cloud", deviceId, ipAddress, ex.getMessage());
            localConnectionCache.remove(deviceId);
            return false;
        }
    }
}
