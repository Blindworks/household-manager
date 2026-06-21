package com.household.manager.controller;

import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherReadingHistoryResponse;
import com.household.manager.service.DwdWeatherService;
import com.household.manager.service.WeatherReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-Endpunkte für DWD-Wetter.
 * Basis-URL: /api/v1/weather
 */
@RestController
@RequestMapping("/v1/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final DwdWeatherService dwdWeatherService;
    private final WeatherReadingService weatherReadingService;

    @GetMapping("/overview")
    public ResponseEntity<WeatherOverviewResponse> getOverview() {
        return ResponseEntity.ok(dwdWeatherService.getOverview());
    }

    @GetMapping("/history")
    public ResponseEntity<List<WeatherReadingHistoryResponse>> getHistory() {
        return ResponseEntity.ok(weatherReadingService.getAllReadings());
    }
}
