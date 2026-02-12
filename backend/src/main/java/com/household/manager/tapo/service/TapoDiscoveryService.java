package com.household.manager.tapo.service;

import com.household.manager.tapo.model.TapoDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for discovering Tapo devices via Cloud API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDiscoveryService {

    private final TapoDeviceService tapoDeviceService;

    /**
     * Discovers all Tapo devices registered in the user's cloud account.
     */
    public List<TapoDevice> discoverDevices() {
        log.info("Discovering Tapo devices from cloud account");

        try {
            List<Map<String, Object>> cloudDevices = tapoDeviceService.listDevices();
            log.info("Found {} devices in cloud account", cloudDevices.size());

            return cloudDevices.stream()
                    .map(this::mapToTapoDevice)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.error("Failed to discover devices from cloud", ex);
            throw new RuntimeException("Failed to discover Tapo devices from cloud: " + ex.getMessage(), ex);
        }
    }

    private TapoDevice mapToTapoDevice(Map<String, Object> cloudDevice) {
        TapoDevice device = new TapoDevice();

        // Extract device information from cloud response
        device.setDeviceId((String) cloudDevice.get("deviceId"));
        device.setDeviceName((String) cloudDevice.getOrDefault("alias", cloudDevice.get("deviceName")));
        device.setDeviceType((String) cloudDevice.get("deviceType"));
        device.setModel((String) cloudDevice.get("deviceModel"));

        // Get IP if available (may not be present in cloud response)
        device.setIp((String) cloudDevice.get("deviceIp"));

        // Check if device is online
        Object status = cloudDevice.get("status");
        if (status != null) {
            device.setOnline(status.equals(1) || status.equals("online"));
        }

        // Get firmware version if available
        device.setFwVer((String) cloudDevice.get("fwVer"));

        log.debug("Mapped device: {} ({})", device.getDeviceName(), device.getDeviceId());

        return device;
    }
}
