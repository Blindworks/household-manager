package com.household.manager.zigbee.controller;

import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.dto.ZigbeeHealthResponse;
import com.household.manager.zigbee.dto.ZigbeeMeasurementResponse;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.service.ZigbeeLiveService;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST + SSE für Zigbee-Sensoren. Basis-URL: /api/v1/zigbee
 */
@RestController
@RequestMapping("/v1/zigbee")
@RequiredArgsConstructor
@Slf4j
public class ZigbeeController {

    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final ZigbeeLiveService liveService;
    private final ZigbeeStreamMonitor streamMonitor;

    @GetMapping("/devices")
    public ResponseEntity<List<ZigbeeDeviceResponse>> getDevices() {
        List<ZigbeeDeviceResponse> devices = deviceRepository.findAll().stream()
                .map(this::toDeviceResponse)
                .toList();
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/devices/{friendlyName}/measurements")
    public ResponseEntity<List<ZigbeeMeasurementResponse>> getMeasurements(
            @PathVariable String friendlyName,
            @RequestParam MeasurementType type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        ZigbeeDevice device = deviceRepository.findByFriendlyName(friendlyName).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime start = (from != null) ? from : LocalDateTime.now().minusDays(7);
        LocalDateTime end = (to != null) ? to : LocalDateTime.now();

        List<ZigbeeMeasurementResponse> result = measurementRepository
                .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        device.getId(), type, start, end)
                .stream()
                .map(m -> ZigbeeMeasurementResponse.builder()
                        .measurementType(m.getMeasurementType())
                        .value(m.getValue())
                        .unit(m.getUnit())
                        .measuredAt(m.getMeasuredAt())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveService.subscribe();
    }

    @GetMapping("/health")
    public ResponseEntity<ZigbeeHealthResponse> getHealth() {
        ZigbeeStreamStatus status = streamMonitor.status();
        return ResponseEntity.ok(ZigbeeHealthResponse.builder()
                .health(status.health().name())
                .healthy(status.healthy())
                .lastMessageAt(status.lastMessageAt())
                .silentMinutes(status.silentMinutes())
                .bridgeState(status.bridgeState())
                .offlineDevices(status.offlineDevices())
                .build());
    }

    private ZigbeeDeviceResponse toDeviceResponse(ZigbeeDevice device) {
        return ZigbeeDeviceResponse.builder()
                .id(device.getId())
                .friendlyName(device.getFriendlyName())
                .ieeeAddress(device.getIeeeAddress())
                .deviceType(device.getDeviceType())
                .model(device.getModel())
                .lastBatteryPercent(device.getLastBatteryPercent())
                .lastLinkQuality(device.getLastLinkQuality())
                .lastSeen(device.getLastSeen())
                .build();
    }
}
