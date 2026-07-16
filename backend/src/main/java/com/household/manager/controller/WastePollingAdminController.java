package com.household.manager.controller;

import com.household.manager.dto.WastePollingStatusResponse;
import com.household.manager.service.WasteCalendarPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Endpunkte zur Steuerung des Muellabfuhr-Kalenderabrufs.
 * Basis-URL: /api/v1/admin/waste-polling
 */
@RestController
@RequestMapping("/v1/admin/waste-polling")
@RequiredArgsConstructor
@Slf4j
public class WastePollingAdminController {

    private final WasteCalendarPollingService pollingService;

    @GetMapping
    public ResponseEntity<WastePollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Muellabfuhr-Kalenderabruf wird manuell ausgeloest");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
