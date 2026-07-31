package com.household.manager.controller;

import com.household.manager.dto.CurrentTemperatureReading;
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

    /**
     * Zeitreihe genau eines Sensors für den Verlaufsgraphen im Detaildialog.
     *
     * <p>sensorId ist bewusst ein Query-Parameter und keine Pfadvariable: die IDs tragen einen
     * Doppelpunkt ("zigbee:12", "alexa:&lt;applianceId&gt;"), und die Alexa-Appliance-ID kommt
     * unkontrolliert aus der Amazon-API. Enthielte sie ein "/" oder "=", zerlegte sie ein
     * Pfadsegment und der Endpunkt wäre für genau diese Sensoren still kaputt.
     */
    @GetMapping("/series")
    public TemperatureSensorSeries getSensorSeries(
            @RequestParam String sensorId,
            @RequestParam(required = false, defaultValue = "DAY") TemperatureRange range) {
        return temperatureSeriesService.getSensorSeries(sensorId, range);
    }

    @GetMapping("/current")
    public List<CurrentTemperatureReading> getCurrentTemperatures() {
        return temperatureSeriesService.getCurrent();
    }
}
