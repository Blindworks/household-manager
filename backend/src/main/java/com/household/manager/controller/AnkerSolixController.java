package com.household.manager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.ankersolix.AnkerSolixAutoControlService;
import com.household.manager.ankersolix.AnkerSolixLiveStreamService;
import com.household.manager.ankersolix.AnkerSolixService;
import com.household.manager.ankersolix.dto.AnkerSolixAutoControlSettingsDto;
import com.household.manager.ankersolix.dto.AnkerSolixAutoControlStatusDto;
import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import com.household.manager.ankersolix.dto.AnkerSolixEnergyDayDto;
import com.household.manager.ankersolix.dto.SolixAutoControlReadingResponse;
import com.household.manager.model.entity.SolixAutoControlReading;
import com.household.manager.repository.SolixAutoControlReadingRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for Anker Solix solar system integration.
 * Base URL: /api/v1/ankersolix
 */
@RestController
@RequestMapping("/v1/ankersolix")
@ConditionalOnProperty(name = "ankersolix.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class AnkerSolixController {

    private final AnkerSolixService ankerSolixService;
    private final AnkerSolixLiveStreamService ankerSolixLiveStreamService;
    private final SolixAutoControlReadingRepository autoControlReadingRepository;

    @Autowired(required = false)
    private AnkerSolixAutoControlService autoControlService;

    /**
     * SSE stream with live power-flow data.
     * Polling interval is configured via {@code ankersolix.live.interval-ms}.
     */
    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return ankerSolixLiveStreamService.subscribe();
    }

    /**
     * Daily energy breakdown for the given date.
     *
     * @param date the day to query, defaults to today
     */
    @GetMapping("/energy")
    public ResponseEntity<AnkerSolixEnergyDayDto> getEnergy(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate queryDate = date != null ? date : LocalDate.now();
        log.debug("Fetching energy data for {}", queryDate);
        return ResponseEntity.ok(ankerSolixService.getEnergyDay(queryDate));
    }

    /**
     * Current device operating parameters (min, max, current output power).
     */
    @GetMapping("/device-params")
    public ResponseEntity<AnkerSolixDeviceParamDto> getDeviceParams() {
        return ResponseEntity.ok(ankerSolixService.getDeviceParams());
    }

    /**
     * Sets the solarbank output power for all schedule time slots.
     *
     * @param request body containing the desired watts value
     */
    @PostMapping("/output-power")
    public ResponseEntity<Void> setOutputPower(@RequestBody SetOutputPowerRequest request) {
        log.info("Setting output power to {} W", request.getWatts());
        ankerSolixService.setOutputPower(request.getWatts());
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current status of the auto-control regulation.
     * Returns enabled=false if auto-control is not active.
     */
    @GetMapping("/auto-control/status")
    public ResponseEntity<AnkerSolixAutoControlStatusDto> getAutoControlStatus() {
        if (autoControlService == null) {
            return ResponseEntity.ok(AnkerSolixAutoControlStatusDto.builder()
                    .enabled(false)
                    .build());
        }
        return ResponseEntity.ok(autoControlService.getStatus());
    }

    /**
     * Returns the current auto-control settings.
     */
    @GetMapping("/auto-control/settings")
    public ResponseEntity<AnkerSolixAutoControlSettingsDto> getAutoControlSettings() {
        if (autoControlService == null) {
            return ResponseEntity.ok(AnkerSolixAutoControlSettingsDto.builder()
                    .enabled(false)
                    .build());
        }
        return ResponseEntity.ok(autoControlService.getSettings());
    }

    /**
     * Updates the auto-control settings at runtime.
     */
    @PutMapping("/auto-control/settings")
    public ResponseEntity<AnkerSolixAutoControlSettingsDto> updateAutoControlSettings(
            @RequestBody AnkerSolixAutoControlSettingsDto settings) {
        if (autoControlService == null) {
            return ResponseEntity.notFound().build();
        }
        autoControlService.updateSettings(settings);
        return ResponseEntity.ok(autoControlService.getSettings());
    }

    /**
     * Returns historical auto-control readings for charting.
     * Defaults to the last 24 hours if no parameters are given.
     */
    @GetMapping("/auto-control/readings")
    public ResponseEntity<List<SolixAutoControlReadingResponse>> getAutoControlReadings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        LocalDateTime end = to != null ? to : LocalDateTime.now();
        LocalDateTime start = from != null ? from : end.minusHours(24);

        List<SolixAutoControlReadingResponse> readings = autoControlReadingRepository
                .findByTimestampBetweenOrderByTimestampAsc(start, end)
                .stream()
                .map(this::toReadingResponse)
                .toList();
        return ResponseEntity.ok(readings);
    }

    private SolixAutoControlReadingResponse toReadingResponse(SolixAutoControlReading r) {
        return new SolixAutoControlReadingResponse(
                r.getId(), r.getTimestamp(), r.getGridPowerW(), r.getCurrentOutputW(),
                r.getTargetOutputW(), r.getClampedOutputW(), r.getMinLoadW(), r.getMaxLoadW(),
                r.isApplied());
    }

    /**
     * Returns the raw GET_SITE_HOMEPAGE response – use this to discover the real field names.
     */
    @GetMapping("/debug/homepage")
    public ResponseEntity<JsonNode> debugHomepage() {
        return ResponseEntity.ok(ankerSolixService.getRawHomepage());
    }

    /**
     * Returns the raw GET_SCENE_INFO response – often has the real-time device power data.
     */
    @GetMapping("/debug/scene")
    public ResponseEntity<JsonNode> debugScene() {
        return ResponseEntity.ok(ankerSolixService.getRawSceneInfo());
    }

    /** Returns the raw GET_SYSTEM_INFO response for device discovery. */
    @GetMapping("/debug/system")
    public ResponseEntity<JsonNode> debugSystem() {
        return ResponseEntity.ok(ankerSolixService.getRawSystemInfo());
    }

    /**
     * Returns the raw GET_DEVICE_PARM response for a given param_type.
     * Useful for discovering API fields (e.g. force-discharge settings).
     * Default param_type is "4" (schedule).
     */
    @GetMapping("/debug/device-params")
    public ResponseEntity<JsonNode> debugDeviceParams(
            @RequestParam(defaultValue = "4") String paramType) {
        return ResponseEntity.ok(ankerSolixService.getRawDeviceParams(paramType));
    }

    /** Returns the raw ENERGY_ANALYSIS response to discover real field names. */
    @GetMapping("/debug/energy")
    public ResponseEntity<JsonNode> debugEnergy(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(ankerSolixService.getRawEnergyDay(queryDate));
    }

    /**
     * Manually enables or disables force-discharge (Entladung nur durch Batterie).
     */
    @PostMapping("/force-discharge")
    public ResponseEntity<Void> setForceDischarge(@RequestBody ForceDischargeRequest request) {
        log.info("Manual force-discharge request: enabled={}", request.isEnabled());
        if (autoControlService != null) {
            autoControlService.setForceDischargeManual(request.isEnabled());
        } else {
            ankerSolixService.setForceDischarge(request.isEnabled());
        }
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Inner request DTOs
    // -------------------------------------------------------------------------

    @Data
    public static class SetOutputPowerRequest {
        private int watts;
    }

    @Data
    public static class ForceDischargeRequest {
        private boolean enabled;
    }
}
