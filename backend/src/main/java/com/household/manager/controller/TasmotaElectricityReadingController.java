package com.household.manager.controller;

import com.household.manager.dto.TasmotaElectricityReadingRequest;
import com.household.manager.dto.TasmotaElectricityReadingResponse;
import com.household.manager.service.TasmotaElectricityReadingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST Controller for Tasmota electricity meter readings.
 * <p>
 * Base URL: /api/v1/tasmota-electricity-readings
 */
@RestController
@RequestMapping("/v1/tasmota-electricity-readings")
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityReadingController {

    private final TasmotaElectricityReadingService tasmotaReadingService;

    /**
     * Create a new Tasmota electricity reading.
     * <p>
     * POST /api/v1/tasmota-electricity-readings
     */
    @PostMapping
    public ResponseEntity<TasmotaElectricityReadingResponse> createReading(
            @Valid @RequestBody TasmotaElectricityReadingRequest request) {
        log.info("Received request to create Tasmota electricity reading");
        TasmotaElectricityReadingResponse response = tasmotaReadingService.createReading(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all Tasmota electricity readings.
     * <p>
     * GET /api/v1/tasmota-electricity-readings
     */
    @GetMapping
    public ResponseEntity<List<TasmotaElectricityReadingResponse>> getAllReadings() {
        log.info("Received request to get all Tasmota electricity readings");
        return ResponseEntity.ok(tasmotaReadingService.getAllReadings());
    }

    /**
     * Get the most recent Tasmota electricity reading.
     * <p>
     * GET /api/v1/tasmota-electricity-readings/latest
     */
    @GetMapping("/latest")
    public ResponseEntity<TasmotaElectricityReadingResponse> getLatestReading() {
        log.info("Received request to get latest Tasmota electricity reading");
        TasmotaElectricityReadingResponse response = tasmotaReadingService.getLatestReading();
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }
}
