package com.household.manager.controller;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.service.AlexaAirQualityReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Messwerte der Amazon Smart Air Quality Monitore.
 * Base URL: /api/v1/alexa/air-quality
 */
@RestController
@RequestMapping("/v1/alexa/air-quality")
@RequiredArgsConstructor
public class AlexaAirQualityController {

    private final AlexaAirQualityReadingService readingService;

    @GetMapping("/latest")
    public List<AlexaAirQualityReadingResponse> getLatest() {
        return readingService.getLatestPerDevice();
    }

    @GetMapping("/readings")
    public List<AlexaAirQualityReadingResponse> getReadings() {
        return readingService.getAllReadings();
    }
}
