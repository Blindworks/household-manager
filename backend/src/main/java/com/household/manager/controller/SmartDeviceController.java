package com.household.manager.controller;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.KasaManualAddRequest;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.dto.SmartDeviceScanRequest;
import com.household.manager.dto.SmartDeviceUpdateRequest;
import com.household.manager.dto.TapoAddressRequest;
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
     * Manually add (or update) a single Kasa device by its IP address.
     * <p>
     * Bypasses UDP broadcast discovery, which does not work in environments where it is
     * blocked (e.g. the production backend running inside a Docker bridge network) even
     * though a direct TCP connection to the device works fine.
     *
     * @param request the IPv4 address of the device to probe
     * @return the persisted device
     */
    @PostMapping("/kasa")
    public ResponseEntity<SmartDeviceResponse> addKasaDeviceByIp(
            @Valid @RequestBody KasaManualAddRequest request) {
        log.info("POST /api/devices/kasa - Adding Kasa device manually at {}", request.getIp());

        SmartDeviceResponse device = smartDeviceService.addKasaDeviceByIp(request.getIp());
        auditService.record("device.kasa.add-manual", request.getIp());

        log.info("Successfully added Kasa device manually: {}", device.getDeviceName());
        return ResponseEntity.status(HttpStatus.CREATED).body(device);
    }

    /**
     * Manually set (or correct) a Tapo device's IP address by probing it directly.
     * <p>
     * Counterpart to {@link #addKasaDeviceByIp} for Tapo devices, whose local UDP broadcast
     * discovery cannot reach them in environments where broadcast is blocked (e.g. the
     * production backend running inside a Docker bridge network) even though a direct KLAP/AES
     * connection to the device works fine. Unlike Kasa, this corrects an already-known device
     * (identified by its cloud {@code deviceId}) rather than creating a new one.
     *
     * @param id the device ID
     * @param request the IPv4 address to probe and persist
     * @return the updated device
     */
    @PutMapping("/{id}/address")
    public ResponseEntity<SmartDeviceResponse> setTapoDeviceAddress(
            @PathVariable Long id,
            @Valid @RequestBody TapoAddressRequest request) {
        log.info("PUT /api/devices/{}/address - Setting Tapo device address to {}", id, request.getIp());

        SmartDeviceResponse device = smartDeviceService.setTapoDeviceAddress(id, request.getIp());
        auditService.record("device.tapo.address.set", "deviceId=" + id + ", ip=" + request.getIp());

        log.info("Successfully set Tapo device address: {}", device.getDeviceName());
        return ResponseEntity.ok(device);
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
