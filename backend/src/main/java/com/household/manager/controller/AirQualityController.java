package com.household.manager.controller;

import com.household.manager.dto.AirQualityOverviewResponse;
import com.household.manager.service.UbaAirQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-Endpunkt für den UBA-Luftqualitätsindex.
 * Basis-URL: /api/v1/air-quality
 */
@RestController
@RequestMapping("/v1/air-quality")
@RequiredArgsConstructor
public class AirQualityController {

    private final UbaAirQualityService ubaAirQualityService;

    @GetMapping("/overview")
    public ResponseEntity<AirQualityOverviewResponse> getOverview() {
        return ResponseEntity.ok(ubaAirQualityService.getOverview());
    }
}
