package com.household.manager.controller;

import com.household.manager.dto.TasmotaElectricityPollingStatusResponse;
import com.household.manager.dto.TasmotaElectricityPollingUpdateRequest;
import com.household.manager.service.TasmotaElectricityPollingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for controlling Tasmota electricity polling.
 * Base URL: /api/v1/admin/tasmota-electricity-polling
 */
@RestController
@RequestMapping("/v1/admin/tasmota-electricity-polling")
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityPollingAdminController {

    private final TasmotaElectricityPollingService pollingService;

    @GetMapping
    public ResponseEntity<TasmotaElectricityPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PutMapping
    public ResponseEntity<TasmotaElectricityPollingStatusResponse> update(
            @Valid @RequestBody TasmotaElectricityPollingUpdateRequest request) {
        log.info("Updating Tasmota electricity polling config");
        return ResponseEntity.ok(pollingService.updateConfig(request));
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering Tasmota electricity polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
