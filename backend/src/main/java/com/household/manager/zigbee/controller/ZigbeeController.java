package com.household.manager.zigbee.controller;

import com.household.manager.zigbee.dto.FlowReferenceResponse;
import com.household.manager.zigbee.dto.ZigbeeBridgeEventResponse;
import com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.dto.ZigbeeHealthResponse;
import com.household.manager.zigbee.dto.ZigbeeMeasurementResponse;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.service.ZigbeeDeviceQueryService;
import com.household.manager.zigbee.service.ZigbeeFlowReferenceService;
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
    private final ZigbeeDeviceQueryService queryService;
    private final ZigbeeFlowReferenceService flowReferenceService;

    @GetMapping("/devices")
    public ResponseEntity<List<ZigbeeDeviceResponse>> getDevices() {
        return ResponseEntity.ok(queryService.listDevices());
    }

    @GetMapping("/bridge")
    public ResponseEntity<ZigbeeBridgeStatusResponse> getBridge() {
        return ResponseEntity.ok(queryService.bridgeStatus());
    }

    @GetMapping("/bridge/events")
    public ResponseEntity<List<ZigbeeBridgeEventResponse>> getBridgeEvents() {
        return ResponseEntity.ok(queryService.bridgeEvents());
    }

    /** Friendly Name als Query-Parameter, weil z2m '/' im Namen erlaubt. */
    @GetMapping("/flow-references")
    public ResponseEntity<List<FlowReferenceResponse>> getFlowReferences(@RequestParam String friendlyName) {
        return ResponseEntity.ok(flowReferenceService.references(friendlyName));
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
                .lastBridgeStateAt(status.lastBridgeStateAt())
                .offlineDevices(status.offlineDevices())
                .build());
    }
}
