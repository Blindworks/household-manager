package com.household.manager.controller;

import com.household.manager.dto.AlexaAirQualityPollingStatusResponse;
import com.household.manager.service.AlexaAirQualityPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for controlling Alexa air quality polling.
 * Base URL: /api/v1/admin/alexa-air-quality-polling
 */
@RestController
@RequestMapping("/v1/admin/alexa-air-quality-polling")
@RequiredArgsConstructor
@Slf4j
public class AlexaAirQualityPollingAdminController {

    private final AlexaAirQualityPollingService pollingService;

    @GetMapping
    public ResponseEntity<AlexaAirQualityPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering Alexa air quality polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
