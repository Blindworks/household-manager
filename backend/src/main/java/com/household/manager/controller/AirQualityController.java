package com.household.manager.controller;

import com.household.manager.dto.AirQualityOverviewResponse;
import com.household.manager.dto.AirQualitySensorSeries;
import com.household.manager.service.AirQualitySeriesService;
import com.household.manager.service.SeriesRange;
import com.household.manager.service.UbaAirQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-Endpunkt für den UBA-Luftqualitätsindex.
 * Basis-URL: /api/v1/air-quality
 */
@RestController
@RequestMapping("/v1/air-quality")
@RequiredArgsConstructor
public class AirQualityController {

    private final UbaAirQualityService ubaAirQualityService;
    private final AirQualitySeriesService airQualitySeriesService;

    @GetMapping("/overview")
    public ResponseEntity<AirQualityOverviewResponse> getOverview() {
        return ResponseEntity.ok(ubaAirQualityService.getOverview());
    }

    /**
     * Zeitreihen der eigenen Luftsensorik (Airrohr draussen, Amazon-Monitore drinnen),
     * serverseitig auf Buckets gemittelt. Speist die Wandtablet-Ansicht "Luftqualitaet".
     */
    @GetMapping("/series")
    public List<AirQualitySensorSeries> getSeries(
            @RequestParam(required = false, defaultValue = "WEEK") SeriesRange range) {
        return airQualitySeriesService.getSeries(range);
    }
}
