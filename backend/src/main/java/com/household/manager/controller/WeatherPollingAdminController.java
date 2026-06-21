package com.household.manager.controller;

import com.household.manager.dto.WeatherPollingStatusResponse;
import com.household.manager.service.WeatherPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Endpunkte zur Steuerung des Wetter-Pollings.
 * Basis-URL: /api/v1/admin/weather-polling
 */
@RestController
@RequestMapping("/v1/admin/weather-polling")
@RequiredArgsConstructor
@Slf4j
public class WeatherPollingAdminController {

    private final WeatherPollingService pollingService;

    @GetMapping
    public ResponseEntity<WeatherPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering weather polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
