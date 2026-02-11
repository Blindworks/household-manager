package com.household.manager.controller;

import com.household.manager.dto.AirrohrPollingStatusResponse;
import com.household.manager.service.AirrohrPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for controlling Airrohr polling.
 * Base URL: /api/v1/admin/airrohr-polling
 */
@RestController
@RequestMapping("/v1/admin/airrohr-polling")
@RequiredArgsConstructor
@Slf4j
public class AirrohrPollingAdminController {

    private final AirrohrPollingService pollingService;

    @GetMapping
    public ResponseEntity<AirrohrPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering Airrohr polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
