package com.household.manager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.dto.SmartDeviceUpdateRequest;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing smart home devices across multiple platforms.
 * <p>
 * Provides unified device discovery, persistence, and control for Kasa, Tapo, and Meross devices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmartDeviceService {

    private final SmartDeviceRepository smartDeviceRepository;
    private final KasaService kasaService;
    private final KasaDiscoveryService kasaDiscoveryService;
    private final MerossDeviceService merossDeviceService;
    private final ObjectMapper objectMapper;

    /**
     * Get all smart devices from the database.
     *
     * @return list of all devices sorted by type and name
     */
    @Transactional(readOnly = true)
    public List<SmartDeviceResponse> getAllDevices() {
        log.debug("Retrieving all smart devices");
        List<SmartDevice> devices = smartDeviceRepository.findAllByOrderByDeviceTypeAscDeviceNameAsc();
        return devices.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific smart device by ID.
     *
     * @param id the device ID
     * @return the device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional(readOnly = true)
    public SmartDeviceResponse getDeviceById(Long id) {
        log.debug("Retrieving smart device with ID: {}", id);
        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));
        return toResponse(device);
    }

    /**
     * Scan for devices and persist them to the database.
     * <p>
     * Discovers devices of the specified type and creates or updates database records.
     *
     * @param deviceType the type of devices to scan for
     * @return list of discovered and persisted devices
     */
    @Transactional
    public List<SmartDeviceResponse> scanAndPersistDevices(DeviceType deviceType) {
        log.info("Scanning for {} devices", deviceType);

        List<SmartDevice> persistedDevices = switch (deviceType) {
            case KASA -> scanKasaDevices();
            //FIXME
            case TAPO -> null; /*scanTapoDevices()*/
            case MEROSS -> scanMerossDevices();
        };

        log.info("Scanned and persisted {} {} devices", persistedDevices.size(), deviceType);
        return persistedDevices.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update a smart device's name and metadata.
     *
     * @param id the device ID
     * @param request the update request
     * @return updated device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public SmartDeviceResponse updateDevice(Long id, SmartDeviceUpdateRequest request) {
        log.info("Updating smart device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        device.setDeviceName(request.getDeviceName());
        if (request.getMetadata() != null) {
            device.setMetadata(serializeMetadata(request.getMetadata()));
        }

        SmartDevice updated = smartDeviceRepository.save(device);
        log.info("Successfully updated device: {}", updated.getDeviceName());
        return toResponse(updated);
    }

    /**
     * Delete a smart device from the database.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void deleteDevice(Long id) {
        log.info("Deleting smart device with ID: {}", id);
        if (!smartDeviceRepository.existsById(id)) {
            throw new IllegalArgumentException("Device not found with ID: " + id);
        }
        smartDeviceRepository.deleteById(id);
        log.info("Successfully deleted device with ID: {}", id);
    }

    /**
     * Refresh a device's state by fetching current status from the physical device.
     *
     * @param id the device ID
     * @return updated device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public SmartDeviceResponse refreshDeviceState(Long id) {
        log.info("Refreshing device state for ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> refreshKasaDeviceState(device);
                //case TAPO -> refreshTapoDeviceState(device);
                case MEROSS -> refreshMerossDeviceState(device);
            }

            SmartDevice updated = smartDeviceRepository.save(device);
            log.info("Successfully refreshed device state for: {}", device.getDeviceName());
            return toResponse(updated);
        } catch (Exception ex) {
            log.error("Failed to refresh device state for ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to refresh device state: " + ex.getMessage(), ex);
        }
    }

    /**
     * Turn on a smart device.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void turnOn(Long id) {
        log.info("Turning on device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> kasaService.turnOn(device.getExternalDeviceId());
                //case TAPO -> tapoDeviceService.turnOn(device.getExternalDeviceId());
                case MEROSS -> merossDeviceService.turnOn(device.getExternalDeviceId());
            }

            device.setPoweredOn(true);
            smartDeviceRepository.save(device);
            log.info("Successfully turned on device: {}", device.getDeviceName());
        } catch (Exception ex) {
            log.error("Failed to turn on device ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to turn on device: " + ex.getMessage(), ex);
        }
    }

    /**
     * Turn off a smart device.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void turnOff(Long id) {
        log.info("Turning off device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> kasaService.turnOff(device.getExternalDeviceId());
                //case TAPO -> tapoDeviceService.turnOff(device.getExternalDeviceId());
                case MEROSS -> merossDeviceService.turnOff(device.getExternalDeviceId());
            }

            device.setPoweredOn(false);
            smartDeviceRepository.save(device);
            log.info("Successfully turned off device: {}", device.getDeviceName());
        } catch (Exception ex) {
            log.error("Failed to turn off device ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to turn off device: " + ex.getMessage(), ex);
        }
    }

    // ==================== Kasa Device Methods ====================

    private List<SmartDevice> scanKasaDevices() {
        List<KasaDiscoveryDto> discovered = kasaDiscoveryService.discover();
        log.info("Discovered {} Kasa devices", discovered.size());

        return discovered.stream()
                .map(this::upsertKasaDevice)
                .collect(Collectors.toList());
    }

    private SmartDevice upsertKasaDevice(KasaDiscoveryDto dto) {
        String externalId = dto.getIp();  // For Kasa, IP is the unique identifier
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing Kasa device: {}", dto.getAlias());
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.KASA);
            device.setExternalDeviceId(externalId);
            log.debug("Creating new Kasa device: {}", dto.getAlias());
        }

        device.setDeviceName(dto.getAlias() != null ? dto.getAlias() : "Kasa Device");
        device.setModel(dto.getModel());
        device.setIpAddress(dto.getIp());
        device.setOnline(true);  // If discovered, it's online
        device.setPoweredOn(dto.isRelayState());
        device.setCapabilities("SWITCH");

        // Store additional metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", dto.getDeviceId());
        device.setMetadata(serializeMetadata(metadata));

        return smartDeviceRepository.save(device);
    }

    private void refreshKasaDeviceState(SmartDevice device) {
        try {
            KasaStatusDto status = kasaService.getStatus(device.getExternalDeviceId());
            device.setOnline(true);
            device.setPoweredOn(status.relayState());
            device.setDeviceName(status.alias() != null ? status.alias() : device.getDeviceName());
        } catch (Exception ex) {
            log.warn("Kasa device {} appears offline: {}", device.getExternalDeviceId(), ex.getMessage());
            device.setOnline(false);
        }
    }

    // ==================== Meross Device Methods ====================

    private List<SmartDevice> scanMerossDevices() {
        List<MerossPlugResponse> discovered = merossDeviceService.discoverPlugs();
        log.info("Discovered {} Meross devices", discovered.size());

        return discovered.stream()
                .map(this::upsertMerossDevice)
                .collect(Collectors.toList());
    }

    private SmartDevice upsertMerossDevice(MerossPlugResponse dto) {
        String externalId = dto.deviceId();
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.MEROSS, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing Meross device: {}", dto.name());
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.MEROSS);
            device.setExternalDeviceId(externalId);
            log.debug("Creating new Meross device: {}", dto.name());
        }

        device.setDeviceName(dto.name() != null ? dto.name() : "Meross Device");
        device.setOnline("online".equalsIgnoreCase(dto.onlineStatus()));
        device.setPoweredOn(dto.on());
        device.setCapabilities("SWITCH");

        // Store additional metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceType", dto.deviceType());
        metadata.put("onlineStatus", dto.onlineStatus());
        device.setMetadata(serializeMetadata(metadata));

        return smartDeviceRepository.save(device);
    }

    private void refreshMerossDeviceState(SmartDevice device) {
        try {
            MerossPlugResponse status = merossDeviceService.getStatus(device.getExternalDeviceId());
            device.setOnline("online".equalsIgnoreCase(status.onlineStatus()));
            device.setPoweredOn(status.on());
            device.setDeviceName(status.name() != null ? status.name() : device.getDeviceName());
        } catch (Exception ex) {
            log.warn("Meross device {} appears offline: {}", device.getExternalDeviceId(), ex.getMessage());
            device.setOnline(false);
        }
    }

    // ==================== Helper Methods ====================

    private SmartDeviceResponse toResponse(SmartDevice entity) {
        return SmartDeviceResponse.builder()
                .id(entity.getId())
                .deviceType(entity.getDeviceType().name())
                .externalDeviceId(entity.getExternalDeviceId())
                .deviceName(entity.getDeviceName())
                .model(entity.getModel())
                .ipAddress(entity.getIpAddress())
                .isOnline(entity.isOnline())
                .isPoweredOn(entity.isPoweredOn())
                .capabilities(parseCapabilities(entity.getCapabilities()))
                .metadata(deserializeMetadata(entity.getMetadata()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private List<String> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(capabilities.split(","));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize metadata", ex);
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize metadata", ex);
            return Collections.emptyMap();
        }
    }
}
