package com.household.manager.controller;

import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.service.TemperatureRange;
import com.household.manager.service.TemperatureSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für aggregierte Temperatur-/Feuchte-Zeitreihen. Basis-URL: /api/v1/temperatures
 */
@RestController
@RequestMapping("/v1/temperatures")
@RequiredArgsConstructor
public class TemperatureController {

    private final TemperatureSeriesService temperatureSeriesService;

    @GetMapping
    public List<TemperatureSensorSeries> getTemperatures(
            @RequestParam(required = false, defaultValue = "WEEK") TemperatureRange range) {
        return temperatureSeriesService.getSeries(range);
    }
}
