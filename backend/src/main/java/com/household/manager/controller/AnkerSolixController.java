package com.household.manager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.ankersolix.AnkerSolixLiveStreamService;
import com.household.manager.ankersolix.AnkerSolixService;
import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import com.household.manager.ankersolix.dto.AnkerSolixEnergyDayDto;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;

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

    // -------------------------------------------------------------------------
    // Inner request DTO
    // -------------------------------------------------------------------------

    @Data
    public static class SetOutputPowerRequest {
        private int watts;
    }
}
