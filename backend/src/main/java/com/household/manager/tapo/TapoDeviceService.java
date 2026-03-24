package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDeviceService {

    private final TapoCloudService tapoCloudService;

    public List<TapoCloudDevice> discoverDevices() {
        return tapoCloudService.getTapoDevices();
    }

    public TapoDeviceState getStatus(String deviceId) {
        TapoCloudDevice cloudDevice = tapoCloudService.getTapoDevices().stream()
                .filter(device -> deviceId.equals(device.deviceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tapo-Geraet nicht gefunden: " + deviceId));

        JsonNode deviceInfo = tapoCloudService.getDeviceInfo(deviceId);
        return TapoDeviceState.from(deviceInfo, cloudDevice, tapoCloudService);
    }

    public void turnOn(String deviceId) {
        tapoCloudService.setDevicePowered(deviceId, true);
        log.info("Tapo device switched on (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        tapoCloudService.setDevicePowered(deviceId, false);
        log.info("Tapo device switched off (deviceId={})", deviceId);
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
}
