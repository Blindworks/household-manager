package com.household.manager.controller;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.dto.SmartDeviceScanRequest;
import com.household.manager.dto.SmartDeviceUpdateRequest;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.service.SmartDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for smart device management.
 * <p>
 * Provides endpoints for device discovery, control, and persistence across
 * Kasa, Tapo, and Meross device ecosystems.
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
@Slf4j
public class SmartDeviceController {

    private final SmartDeviceService smartDeviceService;
    private final AuditService auditService;

    /**
     * Get all smart devices from the database.
     *
     * @return list of all devices
     */
    @GetMapping
    public ResponseEntity<List<SmartDeviceResponse>> getAllDevices() {
        log.info("GET /api/devices - Retrieving all smart devices");
        List<SmartDeviceResponse> devices = smartDeviceService.getAllDevices();
        log.info("Returning {} devices", devices.size());
        return ResponseEntity.ok(devices);
    }

    /**
     * Get a specific smart device by ID.
     *
     * @param id the device ID
     * @return the device details
     */
    @GetMapping("/{id}")
    public ResponseEntity<SmartDeviceResponse> getDeviceById(@PathVariable Long id) {
        log.info("GET /api/devices/{} - Retrieving device", id);
        SmartDeviceResponse device = smartDeviceService.getDeviceById(id);
        return ResponseEntity.ok(device);
    }

    /**
     * Scan for devices of a specific type and persist them to the database.
     *
     * @param request the scan request specifying device type
     * @return list of discovered and persisted devices
     */
    @PostMapping("/scan")
    public ResponseEntity<List<SmartDeviceResponse>> scanDevices(
            @Valid @RequestBody SmartDeviceScanRequest request) {
        log.info("POST /api/devices/scan - Scanning for {} devices", request.getDeviceType());

        DeviceType deviceType = DeviceType.valueOf(request.getDeviceType());
        List<SmartDeviceResponse> devices = smartDeviceService.scanAndPersistDevices(deviceType);

        log.info("Scan complete - found and persisted {} devices", devices.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(devices);
    }

    /**
     * Update a smart device's name and metadata.
     *
     * @param id the device ID
     * @param request the update request
     * @return updated device details
     */
    @PutMapping("/{id}")
    public ResponseEntity<SmartDeviceResponse> updateDevice(
            @PathVariable Long id,
            @Valid @RequestBody SmartDeviceUpdateRequest request) {
        log.info("PUT /api/devices/{} - Updating device", id);
        SmartDeviceResponse updated = smartDeviceService.updateDevice(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a smart device from the database.
     *
     * @param id the device ID
     * @return no content response
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        log.info("DELETE /api/devices/{} - Deleting device", id);
        smartDeviceService.deleteDevice(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Refresh a device's state by fetching current status from the physical device.
     *
     * @param id the device ID
     * @return updated device details
     */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<SmartDeviceResponse> refreshDeviceState(@PathVariable Long id) {
        log.info("POST /api/devices/{}/refresh - Refreshing device state", id);
        SmartDeviceResponse refreshed = smartDeviceService.refreshDeviceState(id);
        return ResponseEntity.ok(refreshed);
    }

    /**
     * Turn on a smart device.
     *
     * @param id the device ID
     * @return no content response
     */
    @PostMapping("/{id}/on")
    public ResponseEntity<Void> turnOn(@PathVariable Long id) {
        log.info("POST /api/devices/{}/on - Turning on device", id);
        smartDeviceService.turnOn(id);
        auditService.record("device.on", String.valueOf(id));
        return ResponseEntity.noContent().build();
    }

    /**
     * Turn off a smart device.
     *
     * @param id the device ID
     * @return no content response
     */
    @PostMapping("/{id}/off")
    public ResponseEntity<Void> turnOff(@PathVariable Long id) {
        log.info("POST /api/devices/{}/off - Turning off device", id);
        smartDeviceService.turnOff(id);
        auditService.record("device.off", String.valueOf(id));
        return ResponseEntity.noContent().build();
    }
}
