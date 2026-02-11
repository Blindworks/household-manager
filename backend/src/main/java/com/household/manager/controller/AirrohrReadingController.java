package com.household.manager.controller;

import com.household.manager.dto.AirrohrReadingHistoryResponse;
import com.household.manager.service.AirrohrReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoint for persisted Airrohr readings.
 * Base URL: /api/v1/airrohr-readings
 */
@RestController
@RequestMapping("/v1/airrohr-readings")
@RequiredArgsConstructor
public class AirrohrReadingController {

    private final AirrohrReadingService airrohrReadingService;

    @GetMapping
    public ResponseEntity<List<AirrohrReadingHistoryResponse>> getAllReadings() {
        return ResponseEntity.ok(airrohrReadingService.getAllReadings());
    }
}
